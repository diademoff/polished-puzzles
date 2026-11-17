package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Singles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: singles.
 *
 * The parameters, the ten presets, the decode and validation quirks,
 * `params + seed -> DESC`, the retry fixtures, and the description
 * grammar.
 *
 * The description tests are the RNG and solver-order guard: one Latin
 * rectangle, one shuffle of the cells, and one `o`-element shuffle per
 * black cell. A failed solver gate reshuffles the black cells only; a
 * twenty-first failure redraws the Latin rectangle. A wrong order still
 * gives a valid-looking board, so only the description comparison
 * catches it.
 */
class SinglesDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    private fun handSave(
        desc: String,
        params: String = "5x5de",
        seed: String = "1",
    ): String {
        val records = listOf(
            "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection",
            "VERSION" to "1",
            "GAME" to "Singles",
            "PARAMS" to params,
            "CPARAMS" to params,
            "SEED" to seed,
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
        val expected = oracle.presets("singles")
        val actual = Singles.presets()
        assertEquals(10, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("5x5 Easy", actual[0].title)
        assertEquals("5x5de", actual[0].params)
        assertEquals("5x5 Tricky", actual[1].title)
        assertEquals("5x5dk", actual[1].params)
        assertEquals("12x12 Tricky", actual[9].title)
        assertEquals("12x12dk", actual[9].params)

        assertEquals("5x5de", Singles.encodeParams(Singles.defaultParams(), true))
        assertEquals("5x5", Singles.encodeParams(Singles.defaultParams(), false))

        // The empty parameter string is the default game.
        assertEquals(
            oracle.newGame("singles", "5x5de", SEED).id,
            oracle.newGame("singles", "", SEED).id,
        )
        assertEquals(
            oracle.newGame("singles", "", SEED).id,
            Midend.newGame(Singles, "", SEED).id(),
        )
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/singles/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("singles", golden.string("game"))
            val seed = golden.string("seed")
            assertEquals(SEED, seed)
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 11, entries.size)
            var sawEmpty = false
            var defaultId: String? = null
            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                if (params.isEmpty()) {
                    sawEmpty = true
                    defaultId = id
                }
                val midend = Midend.newGame(Singles, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("singles", params, seed).id,
                    midend.id(),
                )
            }
            assertTrue("the empty params entry", sawEmpty)
            assertEquals(
                "the empty params entry is the default game",
                defaultId,
                goldenEntryId(entries, "5x5de"),
            )
        }

    private fun goldenEntryId(entries: List<JsonValue>, params: String): String {
        for (item in entries) {
            val entry = item as JsonValue.Obj
            if (entry.string("params") == params) return entry.string("id")
        }
        error("no golden entry for '$params'")
    }

    @Test(timeout = 600_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in Singles.presets().map { it.params } + "") {
            for (seed in seeds) {
                val expected = oracle.newGame("singles", params, seed)
                val midend = Midend.newGame(Singles, params, seed)
                assertEquals("seed '$seed' for '$params' id", expected.id, midend.id())
                assertEquals(
                    "seed '$seed' for '$params' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("seed '$seed' for '$params' status", 0, midend.status())
                assertEquals("seed '$seed' for '$params' undo", false, midend.canUndo)
                assertEquals("seed '$seed' for '$params' redo", false, midend.canRedo)
                assertNull("$params has no AUXINFO", midend.auxInfo)
            }
        }
    }

    @Test(timeout = 600_000)
    fun `a numeric seed sweep pins the draw order`() = OracleClient().use { oracle ->
        for (params in Singles.presets().map { it.params } + "") {
            for (seed in 0..40) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("singles", params, seed.toString()).id,
                    Midend.newGame(Singles, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test(timeout = 300_000)
    fun `the retry fixtures and the tiny downgrade match the oracle`() =
        OracleClient().use { oracle ->
            // The engine test pins the attempt counters. These three
            // boards are the fast retry sizes from the spec.
            val retries = listOf(
                "2x20dk" to "1",
                "2x30dk" to "3",
                "3x30dk" to "1",
            )
            for ((params, seed) in retries) {
                assertEquals(
                    "retry '$params' seed $seed",
                    oracle.newGame("singles", params, seed).id,
                    Midend.newGame(Singles, params, seed).id(),
                )
            }

            // `w < 4 || h < 4` and Tricky downgrades the local
            // difficulty only, so CPARAMS keeps the request.
            val tiny = mapOf(
                "2x2dk" to "2x2de",
                "3x3dk" to "3x3de",
                "2x3dk" to "2x3de",
                "3x2dk" to "3x2de",
            )
            for ((params, generated) in tiny) {
                val expected = oracle.newGame("singles", params, "1")
                val local = Midend.newGame(Singles, params, "1")
                assertEquals("$params id", expected.id, local.id())
                assertEquals("$params save", expected.state, local.serialise().serialise())
                val expectedSave = SgtpSave.parse(expected.state)
                val localSave = local.serialise()
                assertEquals("$params PARAMS", expectedSave.value("PARAMS"), localSave.value("PARAMS"))
                assertEquals("$params CPARAMS", expectedSave.value("CPARAMS"), localSave.value("CPARAMS"))
                assertEquals(generated, localSave.value("PARAMS"))
                assertEquals(params, localSave.value("CPARAMS"))
            }
        }

    @Test(timeout = 300_000)
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        // Every accepted text decodes over the game defaults. The digit
        // skip starts at the first character, so a sign or a space hides
        // both the size separator and the difficulty.
        val accepted = listOf(
            "5x5de", "5x5dk", "5", "5dk", "5x5dkk", "5x5dejunk",
            "5x5k", "5x5e", "0005x5de", "05x5dk", "+5x5dk", " 5x5dk",
            "5x5",
        )
        for (params in accepted) {
            val expected = oracle.newGame("singles", params, SEED)
            val midend = Midend.newGame(Singles, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
            val expectedSave = SgtpSave.parse(expected.state)
            assertEquals(
                "PARAMS for '$params'",
                expectedSave.value("PARAMS"),
                midend.serialise().value("PARAMS"),
            )
            assertEquals(
                "CPARAMS for '$params'",
                expectedSave.value("CPARAMS"),
                midend.serialise().value("CPARAMS"),
            )
        }

        // The spacing quirk: a leading space or sign makes both sizes
        // equal and drops the difficulty, so `+5x5dk` is Easy.
        assertEquals(
            Midend.newGame(Singles, "5x5de", SEED).id(),
            Midend.newGame(Singles, " 5x5dk", SEED).id(),
        )
        assertEquals("5x5de", Singles.encodeParams(Singles.decodeParams(" 5x5dk"), true))

        // A `d` with no recognised character decodes to DIFF_MAX and the
        // full validation rejects it. `5d` has no height separator
        // either, so it is 5x5 with the same invalid difficulty.
        val rejected = listOf("5x", "5d", "5x5d", "5x5dK", "-5x5de", "1x1de", "63x63de")
        for (params in rejected) {
            val error = try {
                oracle.newGame("singles", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("C rejects '$params'", error)
            val decoded = Singles.decodeParams(params)
            assertEquals(
                "message for '$params'",
                error,
                Singles.validateParams(decoded, true),
            )
            val kotlinError = try {
                Midend.newGame(Singles, params, SEED)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("Kotlin message for '$params'", error, kotlinError)
        }
    }

    @Test(timeout = 120_000)
    fun `validation keeps the C order and the exact messages`() {
        assertEquals(
            "Width and height must be at least two",
            Singles.validateParams(Singles.Params(1, 99, 0), true),
        )
        assertEquals(
            "Width and height must be at least two",
            Singles.validateParams(Singles.Params(99, 1, 0), true),
        )
        assertEquals(
            "Puzzle is too large",
            Singles.validateParams(Singles.Params(63, 63, 0), true),
        )
        assertEquals(
            "Puzzle is too large",
            Singles.validateParams(Singles.Params(63, 5, 0), true),
        )
        assertNull(Singles.validateParams(Singles.Params(2, 2, 1), true))
        assertNull(Singles.validateParams(Singles.Params(62, 62, 0), true))
        assertNull(Singles.validateParams(Singles.Params(2, 62, 0), true))
        assertEquals(
            "Unknown difficulty rating",
            Singles.validateParams(Singles.Params(5, 5, 2), true),
        )
        assertNull("a short encoding ignores the difficulty", Singles.validateParams(Singles.Params(5, 5, 2), false))
        assertEquals(
            "5x5d",
            Singles.encodeParams(Singles.Params(5, 5, 2), true),
        )
    }

    @Test(timeout = 120_000)
    fun `the description grammar accepts and rejects the C strings`() =
        OracleClient().use { oracle ->
            val accepted = listOf(
                "1".repeat(25),
                "5".repeat(25),
                "1234512345123451234512345",
                "5432154321543215432154321",
            )
            for (desc in accepted) {
                val save = handSave(desc)
                val expected = oracle.status(save)
                val midend = Midend.fromSave(Singles, SgtpSave.parse(save))
                assertEquals("accepted '${desc.take(8)}'", expected.id, midend.id())
                assertEquals(
                    "accepted '${desc.take(8)}' text",
                    oracle.text(save),
                    midend.textFormat(),
                )
            }
            // For 62x2 the bound is o = 62, so 'Z' (61) loads and '['
            // (not in c2n) does not. D-017 rejects generation only, so
            // a hand-written save with 'Z' still loads in both engines.
            val wideDesc = "Z" + "1".repeat(123)
            val wideSave = handSave(wideDesc, params = "62x2de")
            val wideExpected = oracle.status(wideSave)
            val wideMidend = Midend.fromSave(Singles, SgtpSave.parse(wideSave))
            assertEquals("62x2 accepted id", wideExpected.id, wideMidend.id())
            assertEquals(
                "62x2 accepted text",
                oracle.text(wideSave),
                wideMidend.textFormat(),
            )
            assertNull(Singles.validateDesc(Singles.Params(62, 2, 0), wideDesc))
            assertEquals(
                "Invalid character in game description",
                Singles.validateDesc(Singles.Params(62, 2, 0), "[" + "1".repeat(123)),
            )
            assertEquals(
                "Game description is wrong length",
                Singles.validateDesc(Singles.Params(5, 5, 0), "1".repeat(24)),
            )
            assertEquals(
                "Invalid character in game description",
                Singles.validateDesc(Singles.Params(5, 5, 0), "0" + "1".repeat(24)),
            )
            assertEquals(
                "Invalid character in game description",
                Singles.validateDesc(Singles.Params(5, 5, 0), "6" + "1".repeat(24)),
            )
            assertEquals(
                "Invalid character in game description",
                Singles.validateDesc(Singles.Params(5, 5, 0), "a" + "1".repeat(24)),
            )
            assertEquals(
                "Invalid character in game description",
                Singles.validateDesc(Singles.Params(5, 5, 0), "A" + "1".repeat(24)),
            )
            // A rectangle accepts up to max(w,h).
            assertNull(Singles.validateDesc(Singles.Params(2, 5, 0), "5" + "1".repeat(9)))
            assertEquals(
                "Invalid character in game description",
                Singles.validateDesc(Singles.Params(2, 5, 0), "6" + "1".repeat(9)),
            )

            // The C save loader wraps the message. The engine test pins
            // the two raw strings.
            val badSave = handSave("1".repeat(24))
            val wrapped = try {
                oracle.status(badSave)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("Game description in save file is invalid", wrapped)
        }

    @Test(timeout = 120_000)
    fun `a side of 62 is rejected before any draw`() {
        // D-017. C accepts 62x2, generates a description that contains
        // the digit 62 as '[', then aborts in `new_game`. Measured:
        // `62x2de` seed 5 aborts after 40.9 s with
        // `singles.c:1452: new_game: Assertion `!"new_game failed to
        // unpick"' failed.` (SIGABRT, exit 134); seed 19 aborts too.
        // The port rejects generation with the parameter message and
        // leaves the generator untouched.
        for (seed in listOf("1", "5", "19")) {
            for (params in listOf("62x2de", "2x62de", "62x62de")) {
                val random = RandomState.fromSeed(seed)
                val before = random.encodeState()
                val error = try {
                    Singles.newDesc(Singles.decodeParams(params), random)
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                assertEquals("$params seed $seed message", "Puzzle is too large", error)
                assertEquals(
                    "$params seed $seed leaves the generator untouched",
                    before,
                    random.encodeState(),
                )
                assertTrue(
                    "$params seed $seed still passes validateParams",
                    Singles.validateParams(Singles.decodeParams(params), true) == null,
                )
                val midendError = try {
                    Midend.newGame(Singles, params, seed)
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                assertEquals("$params seed $seed midend message", "Puzzle is too large", midendError)
            }
        }

        // The accepted boundary is 61, and 62 is only rejected by the
        // generator, not by the validator.
        val valid = listOf("61x2de", "2x61de")
        for (params in valid) {
            val decoded = Singles.decodeParams(params)
            assertNull("$params validates", Singles.validateParams(decoded, true))
        }
    }

    @Test(timeout = 180_000)
    fun `the largest valid side generates the C description`() =
        OracleClient().use { oracle ->
            // D-017 keeps every side in 2..61. A side of 61 is the
            // largest accepted size, so this is the boundary fixture:
            // seed 3 is the measured fast seed (about six seconds in
            // C); the other seeds did not finish in the budget.
            val params = "61x2de"
            val seed = "3"
            val expected = oracle.newGame("singles", params, seed)
            val actual = Midend.newGame(Singles, params, seed)
            assertEquals("$params id", expected.id, actual.id())
            assertEquals(
                "$params save",
                expected.state,
                actual.serialise().serialise(),
            )
            assertNull(
                "$params description validates",
                Singles.validateDesc(
                    Singles.decodeParams(params),
                    actual.desc,
                ),
            )
        }

    @Test(timeout = 120_000)
    fun `a pasted id starts from the defaults, so CPARAMS is Easy`() {
        // Shared descriptive-id difference. C decodes `5x5:DESC` over
        // the previous long-term parameters; Kotlin starts from the
        // defaults (`midend.c:1874-1898`; `Midend.kt:791`). Only the
        // base difficulty differs: a later save writes `CPARAMS 5x5de`
        // instead of the previous `5x5dk`. The description, the state,
        // and the short id are identical.
        val desc = "5".repeat(25)
        val pasted = Midend.fromGameId(Singles, "5x5:$desc")
        assertEquals(desc, pasted.desc)
        assertEquals("5x5de", pasted.serialise().value("CPARAMS"))

        // The same description loaded from a save keeps the hard
        // difficulty, so the difference is only the pasted-id base.
        val loaded = Midend.fromSave(
            Singles,
            SgtpSave.parse(handSave(desc, params = "5x5dk")),
        )
        assertEquals(desc, loaded.desc)
        assertEquals("5x5dk", loaded.serialise().value("CPARAMS"))
        assertEquals("the short id is the same", pasted.id(), loaded.id())
    }

    private companion object {
        const val SEED = "12345"
    }
}
