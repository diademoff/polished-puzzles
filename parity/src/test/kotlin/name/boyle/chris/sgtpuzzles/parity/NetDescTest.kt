package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: net.
 *
 * Parameters, presets, the decode quirks, the validation order, the
 * frozen descriptions, `params + seed -> DESC` for every preset,
 * a numeric sweep, retry fixtures, and the `AUXINFO` answer. The
 * `AUXINFO` answer pins the unshuffled grid and therefore the whole
 * generator before the shuffle. Generation is gated on the solver, so
 * every description also pins the FIFO deduction order.
 */
class NetDescTest {

    private companion object {
        const val SEED = "12345"

        val SEEDS = listOf(
            "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
        )

        val PRESETS = listOf(
            "5x5", "7x7", "9x9", "11x11",
            "5x5w", "7x7w", "9x9w", "11x11w",
        )
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Net, params, seed).id()

    private fun generated(params: String, seed: String) =
        Net.newDesc(Net.decodeParams(params), RandomState.fromSeed(seed))

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("net")
        val actual = Net.presets()
        assertEquals(8, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(
            listOf(
                "5x5", "7x7", "9x9", "11x11",
                "5x5 wrapping", "7x7 wrapping", "9x9 wrapping", "11x11 wrapping",
            ),
            expected.map { it.title },
        )
        assertEquals(PRESETS, expected.map { it.params })
    }

    @Test(timeout = 180_000)
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/net/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("net", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(9, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, seed))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("net", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test(timeout = 180_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in PRESETS) {
            for (seed in SEEDS) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("net", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test(timeout = 180_000)
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        for (params in listOf("3x3", "3x3w", "4x4", "5x5", "4x3w")) {
            for (seed in 0..12) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("net", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test(timeout = 120_000)
    fun `retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // Each seed reaches a distinct generator retry branch:
        // `5x5` 6 and `4x4` 178 call `perturb`; `7x7` 9 and `5x5w` 2
        // restart at `begin_generation`; `7x7` 247 retries the full
        // shuffle. The descriptions are pinned so a change to a retry
        // condition is visible even if the oracle is rebuilt.
        for ((params, seed, expected) in listOf(
            Triple("5x5", "6", "5x5:3c6594ae283bbe49e6e449234"),
            Triple("7x7", "9", "7x7:41a9272518dd74d38db32b7572e34a879214e513779e415a9"),
            Triple("7x7", "247", "7x7:4111d48cda691527ee33a9ed77b9a8dac36dce2bca1141648"),
            Triple("5x5w", "2", "5x5w:834c99286d43beb4ecb1472d1"),
            Triple("4x4", "178", "4x4:19223d7e5b652614"),
            Triple("4x4w", "4", "4x4w:885b94191c738a77"),
        )) {
            assertEquals("'$params' seed '$seed' pinned", expected, id(params, seed))
            assertEquals(
                "'$params' seed '$seed'",
                oracle.newGame("net", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "5",
            "+5x5",
            "5x5x5",
            "5x5w",
            "5x5a",
            "5x5b.5",
            "5x5b0.5",
            "5x5b1",
            "5x5b1e-3",
            "5x5b",
            "5x5ba",
            "5x5garbage",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("net", params, SEED).id,
                id(params, SEED),
            )
        }

        for (params in listOf(
            "-5x5",
            "0x5",
            "5x5b-0.5",
            "5x5b0x10",
            "5x5b2",
            "1x1",
            "0x0",
            "2x2w",
            "-5x5b2",
        )) {
            val expected = try {
                oracle.newGame("net", params, SEED).id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Net, params, SEED)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        // `bNaN` is C undefined behavior: C accepts it and then aborts
        // on the `(int)` cast. The port rejects it before any
        // generation. This is pinned in the engine test.
        val nan = Net.decodeParams("5x5bNaN") as Net.Params
        assertTrue(nan.barrierProbability.isNaN())
        assertEquals("Barrier probability must be a number", Net.validateParams(nan, true))
    }

    @Test
    fun `the validation order and messages are the C order`() {
        for ((params, message) in listOf(
            "0x0" to "Width and height must both be greater than zero",
            "1x1" to "At least one of width and height must be greater than one",
            "2x2w" to
                "No wrapping puzzle with a width or height of 2 can have a unique solution",
            "-5x5b2" to "Width and height must both be greater than zero",
            "65536x65536" to "Width times height must not be unreasonably large",
            "5x5b-0.5" to "Barrier probability may not be negative",
            "5x5b2" to "Barrier probability may not be greater than 1",
        )) {
            assertEquals(
                "'$params'",
                message,
                Net.validateParams(Net.decodeParams(params), true),
            )
        }

        // The 2-wide wrapping rejection is full-only: a game id and a
        // CPARAMS check accept it, and a save load rejects it.
        assertEquals(
            null,
            Net.validateParams(Net.decodeParams("2x5w"), false),
        )
        assertNotNull(Net.validateParams(Net.decodeParams("2x5w"), true))
    }

    @Test
    fun `the area boundary is accepted without generation`() {
        assertEquals(null, Net.validateParams(Net.Params(46340, 46340, false, true, 0f), true))
        assertEquals(
            "Width times height must not be unreasonably large",
            Net.validateParams(Net.Params(46341, 46341, false, true, 0f), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Net.validateParams(Net.Params(65536, 65536, false, true, 0f), true),
        )
    }

    @Test
    fun `the full encoding keeps the barrier and the flag, the short form drops them`() {
        for ((text, full, short) in listOf(
            Triple("5x5", "5x5", "5x5"),
            Triple("5x5w", "5x5w", "5x5w"),
            Triple("5x5b0.5a", "5x5b0.5a", "5x5"),
            Triple("5x5b0.1", "5x5b0.1", "5x5"),
            Triple("5x5b1", "5x5b1", "5x5"),
            Triple("5x5b0.001", "5x5b0.001", "5x5"),
            Triple("11x11b1", "11x11b1", "11x11"),
            Triple("5x5a", "5x5a", "5x5"),
        )) {
            val params = Net.decodeParams(text)
            assertEquals("'$text' full", full, Net.encodeParams(params, true))
            assertEquals("'$text' short", short, Net.encodeParams(params, false))
        }
    }

    @Test
    fun `the barrier rate uses the C percent-g text`() = OracleClient().use { oracle ->
        for ((text, expected) in listOf(
            "0.1" to "3x3b0.1",
            "0.5" to "3x3b0.5",
            "1" to "3x3b1",
            "0.9999" to "3x3b0.9999",
            "0.001" to "3x3b0.001",
            "0.00001" to "3x3b1e-05",
            "0.3333333" to "3x3b0.333333",
            "0.25" to "3x3b0.25",
            "0.75" to "3x3b0.75",
        )) {
            val params = "3x3b$text"
            val oracleSave = oracle.newGame("net", params, "1").state
            assertEquals(
                "'$params' CPARAMS",
                expected,
                SgtpSave.parse(oracleSave).value("CPARAMS"),
            )
            assertEquals(
                "'$params' Kotlin",
                expected,
                Net.encodeParams(Net.decodeParams(params), true),
            )
        }
    }

    @Test(timeout = 180_000)
    fun `the generated aux matches the oracle auxinfo`() = OracleClient().use { oracle ->
        for ((params, seed) in listOf(
            "5x5" to "12345",
            "5x5w" to "12345",
            "7x7" to "1",
            "5x5b0.5" to "12345",
            "5x5b0.1" to "12345",
            "5x5a" to "12345",
        )) {
            val expected = oracle.newGame("net", params, seed)
            val generated = generated(params, seed)
            assertEquals(
                "'$params' seed '$seed' desc",
                expected.id.substringAfter(':'),
                generated.text,
            )
            val kotlinSave = Midend.newGame(Net, params, seed).serialise()
            assertEquals(
                "'$params' seed '$seed' auxinfo",
                SgtpSave.parse(expected.state).value("AUXINFO"),
                kotlinSave.value("AUXINFO"),
            )
            assertEquals(params.width() * params.height(), generated.auxInfo!!.length)
        }

        // At a fixed seed the barrier rate changes only the barriers, not
        // the unshuffled auxiliary answer.
        val plain = generated("5x5", SEED).auxInfo
        val barriers = generated("5x5b0.5", SEED).auxInfo
        assertEquals(plain, barriers)
    }

    @Test(timeout = 180_000)
    fun `the whole generated save keeps the params split`() =
        OracleClient().use { oracle ->
            for (params in PRESETS + "5x5b0.5a") {
                val save = Midend.newGame(Net, params, SEED).serialise()
                val decoded = Net.decodeParams(params)
                // PARAMS is the long-term form after the short game id
                // round trip; CPARAMS keeps the full requested form.
                // They differ only for a non-preset barrier or `a`.
                assertEquals(
                    "'$params' PARAMS",
                    Net.encodeParams(decoded, false),
                    save.value("PARAMS"),
                )
                assertEquals(
                    "'$params' CPARAMS",
                    Net.encodeParams(decoded, true),
                    save.value("CPARAMS"),
                )
                assertEquals(
                    "'$params' reads back",
                    Midend.newGame(Net, params, SEED).id(),
                    oracle.id(save.serialise()).id,
                )
                assertEquals(
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "AUXINFO", "UI", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }
        }

    @Test(timeout = 180_000)
    fun `generation is deterministic`() {
        for (params in PRESETS) {
            val first = generated(params, SEED)
            val second = generated(params, SEED)
            assertEquals("'$params' text", first.text, second.text)
            assertEquals("'$params' aux", first.auxInfo, second.auxInfo)
        }
        val randomA = RandomState.fromSeed(SEED)
        val randomB = RandomState.fromSeed(SEED)
        Net.newDesc(Net.decodeParams("7x7"), randomA)
        Net.newDesc(Net.decodeParams("7x7"), randomB)
        assertEquals(randomA.encodeState(), randomB.encodeState())
    }
}

/** The board area of a parameter string, for the aux length check. */
private fun String.width(): Int = substringBefore('x').toInt()

private fun String.height(): Int {
    val afterX = substringAfter('x', "")
    if (afterX.isEmpty()) return width()
    return afterX.takeWhile { it in '0'..'9' }.toInt()
}
