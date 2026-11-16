package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: tracks.
 *
 * Parameters, presets, the decode and validation quirks, the
 * `PARAMS`/`CPARAMS` split, and `params + seed -> DESC`.
 *
 * The description tests are the RNG-order guards: the entrance draw,
 * the three-draw direction shuffle per path step, the two
 * `shuffle(positions)` passes, and the solver-gated clue add and strip
 * all change every description when they move.
 */
class TracksDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("tracks")
        val actual = Tracks.presets()
        assertEquals(12, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("8x8 Easy", actual[0].title)
        assertEquals("8x8de", actual[0].params)
        assertEquals("15x15 Hard", actual[11].title)
        assertEquals("15x15dh", actual[11].params)

        val defaults = oracle.newGame("tracks", "", SEED)
        val midend = Midend.newGame(Tracks, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals(defaults.state, midend.serialise().serialise())
        assertEquals("8x8dt", Tracks.encodeParams(midend.params, true))
        assertEquals("8x8dt", Tracks.encodeParams(midend.currentParams, true))
        assertEquals("8x8", Tracks.encodeParams(midend.currentParams, false))
    }

    @Test(timeout = 300_000)
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/tracks/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("tracks", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 13, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                val midend = Midend.newGame(Tracks, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("tracks", params, seed).id,
                    midend.id(),
                )
            }
        }

    @Test(timeout = 300_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = Tracks.presets().map { it.params } + ""
        val seeds = listOf(
            "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
        )
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("tracks", params, seed).id,
                    Midend.newGame(Tracks, params, seed).id(),
                )
            }
        }
    }

    @Test(timeout = 300_000)
    fun `a numeric seed sweep pins the draw order`() = OracleClient().use { oracle ->
        for (params in listOf("8x8de", "8x8dt", "10x10dt", "")) {
            for (seed in 0..19) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("tracks", params, seed.toString()).id,
                    Midend.newGame(Tracks, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `the generator state continues the oracle stream`() = OracleClient().use { oracle ->
        val random = RandomState.fromSeed(SEED)
        Tracks.newDesc(Tracks.decodeParams("8x8dt"), random)
        val encoded = random.encodeState()
        val expected = oracle.randomUpto(8, 6, state = encoded)
        assertEquals(expected.values, (0 until 6).map { random.upto(8) })
        assertEquals(expected.state, random.encodeState())
    }

    @Test(timeout = 300_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine test proves that these seeds reach the two retry
        // paths: `tracks.c:745` (new path) and `tracks.c:447-458`
        // (the internal path restart).
        val fixtures = listOf(
            "8x8dt" to "0",
            "8x8dt" to "1",
            "10x10dt" to "5",
            "8x8de" to "26",
        )
        for ((params, seed) in fixtures) {
            assertEquals(
                "retry fixture '$params' seed $seed",
                oracle.newGame("tracks", params, seed).id,
                Midend.newGame(Tracks, params, seed).id(),
            )
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "8x8",
            "8x8d",
            "8x8dt",
            "8x8de",
            "8x8dh",
            "8x8dq",
            "8x8e",
            "8x8odt",
            "8x8dho",
            "8x8dto",
            "+8x8",
            "+8x10",
            "08x08",
            "8x8junk",
            "8x8dte",
        )
        for (params in accepted) {
            val expected = oracle.newGame("tracks", params, SEED)
            val midend = Midend.newGame(Tracks, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }
    }

    @Test(timeout = 120_000)
    fun `an overflowing atoi saturates`() {
        // C undefined behavior is defined as saturation here. The width
        // is already above the C area limit, so validation rejects it
        // before any generation.
        val params = Tracks.decodeParams("999999999999x4") as Tracks.Params
        assertEquals(Int.MAX_VALUE, params.w)
        assertEquals(4, params.h)
        assertEquals(
            "Width times height must not be unreasonably large",
            Tracks.validateParams(params, full = true),
        )
        assertEquals(
            Int.MIN_VALUE,
            (Tracks.decodeParams("-999999999999x4") as Tracks.Params).w,
        )
    }

    @Test
    fun `validation checks the C order and messages`() {
        val cases = listOf(
            "3x8dt" to "Width and height must both be at least four",
            "8x3dt" to "Width and height must both be at least four",
            "-4x8dt" to "Width and height must both be at least four",
            "0x8dt" to "Width and height must both be at least four",
            "999999x999999dt" to "Width times height must not be unreasonably large",
        )
        for ((params, message) in cases) {
            assertEquals(
                params,
                message,
                Tracks.validateParams(Tracks.decodeParams(params), full = true),
            )
            assertEquals(
                "$params short",
                message,
                Tracks.validateParams(Tracks.decodeParams(params), full = false),
            )
        }
        for (params in listOf("4x4", "4x4dt", "8x8", "8x8dt", "8x8dho")) {
            assertNull(params, Tracks.validateParams(Tracks.decodeParams(params), full = true))
        }
    }

    @Test
    fun `the PARAMS and CPARAMS split keeps the real difficulty`() =
        OracleClient().use { oracle ->
            val tenEasy = Midend.newGame(Tracks, "10x10de", SEED).serialise()
            assertEquals("10x10dt", tenEasy.value("PARAMS"))
            assertEquals("10x10de", tenEasy.value("CPARAMS"))
            assertEquals(
                "10x10:zd6b3mCs5zc9b,2,3,2,3,3,2,3,S4,3,3,2,4,4,S4,3,2,4,1,2,2",
                Midend.newGame(Tracks, "10x10de", SEED).id(),
            )

            val noOnes = Midend.newGame(Tracks, "8x8dto", SEED).serialise()
            assertEquals("8x8dt", noOnes.value("PARAMS"))
            assertEquals("8x8dto", noOnes.value("CPARAMS"))
            assertFalse(Midend.newGame(Tracks, "8x8dto", SEED).id().startsWith("8x8d"))
            assertEquals(
                "8x8",
                Midend.newGame(Tracks, "8x8dto", SEED).id().substringBefore(':'),
            )

            // No AUXINFO and no UI record: the generator never writes
            // `aux` and the game has no `encode_ui`.
            for (text in listOf(tenEasy.serialise(), noOnes.serialise())) {
                val save = SgtpSave.parse(text)
                assertNull("AUXINFO", save.value("AUXINFO"))
                assertNull("UI", save.value("UI"))
                assertEquals(
                    listOf("SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "NSTATES", "STATEPOS"),
                    save.records.map { it.key },
                )
            }
            assertEquals(
                "10x10", Tracks.encodeParams(Midend.newGame(Tracks, "10x10de", SEED).currentParams, false),
            )
        }

    @Test(timeout = 300_000)
    fun `4x4 tricky is generated as easy but keeps the request`() =
        OracleClient().use { oracle ->
            for (seed in listOf("1", "2", "3", "12345")) {
                val easy = Midend.newGame(Tracks, "4x4de", seed)
                val tricky = Midend.newGame(Tracks, "4x4dt", seed)
                assertEquals("seed $seed description", easy.desc, tricky.desc)
                assertEquals("seed $seed id", easy.id(), tricky.id())
                assertEquals("4x4dt", tricky.serialise().value("CPARAMS"))
                assertEquals("4x4dt", tricky.serialise().value("PARAMS"))
                assertEquals(
                    "seed $seed oracle",
                    oracle.newGame("tracks", "4x4dt", seed).id,
                    tricky.id(),
                )
                assertTrue(easy.id().startsWith("4x4:"))
            }
        }

    @Test
    fun `the description grammar errors match the C text`() {
        val params = Tracks.decodeParams("8x8dt")
        val cases = listOf(
            "M" to "Game description contained unexpected characters",
            "1" to "Clue did not provide 2 direction flags",
            "z" to "Not enough numbers given after grid specification",
            "a".repeat(64) + "x" to "Invalid character in number list",
        )
        for ((desc, message) in cases) {
            assertEquals(desc, message, Tracks.validateDesc(params, desc))
        }

        // A clue with three bits and an unknown letter.
        assertEquals(
            "Clue did not provide 2 direction flags",
            Tracks.validateDesc(params, "7" + "a".repeat(63) + ",1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"),
        )
        assertEquals(
            "Game description contained unexpected characters",
            Tracks.validateDesc(params, "M" + "a".repeat(63) + ",1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"),
        )

        // A zero clue and an absent number both pass validation, as in C.
        assertNull(
            Tracks.validateDesc(
                params,
                "a".repeat(64) +
                    ",0,0,0,0,0,0,S0,0" +
                    ",S0,0,0,0,0,0,0,0",
            ),
        )
        assertNull(
            Tracks.validateDesc(
                params,
                "a".repeat(64) + ",S" + ",0".repeat(7) + ",S0" + ",0".repeat(7),
            ),
        )
        assertEquals(
            "Puzzle must have one entrance and one exit",
            Tracks.validateDesc(
                params,
                "a".repeat(64) + ",0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0",
            ),
        )
        assertEquals(
            "Unexpected additional character at end of game description",
            Tracks.validateDesc(
                params,
                "a".repeat(64) + ",S0" + ",0".repeat(7) + ",S0" + ",0".repeat(7) + "x",
            ),
        )
    }

    @Test(timeout = 120_000)
    fun `a description with a zero clue number still decodes`() {
        val desc = "a".repeat(64) +
            ",0,0,0,0,0,0,S0,0" +
            ",S0,0,0,0,0,0,0,0"
        assertNull(Tracks.validateDesc(Tracks.decodeParams("8x8dt"), desc))
        val state = Tracks.newState(Tracks.decodeParams("8x8dt"), desc) as Tracks.State
        assertEquals(0, state.numbers.values[0])
        assertEquals(6, state.numbers.colS)
        assertEquals(0, state.numbers.rowS)
    }

    private companion object {
        const val SEED = "12345"
    }
}
