package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: bridges.
 *
 * Parameters, presets, the decode quirks, the validation order, the
 * frozen descriptions, `params + seed -> DESC` for every preset, a
 * named seed list, and a numeric sweep. The `AUXINFO` answer pins the
 * island creation order and the `game_state_diff` token order.
 */
class BridgesDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Bridges, params, seed).id()

    private fun generated(params: String, seed: String) =
        Bridges.newDesc(Bridges.decodeParams(params), RandomState.fromSeed(seed))

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("bridges")
        val actual = Bridges.presets()
        assertEquals(9, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(
            listOf(
                "7x7 Easy", "7x7 Medium", "7x7 Hard",
                "10x10 Easy", "10x10 Medium", "10x10 Hard",
                "15x15 Easy", "15x15 Medium", "15x15 Hard",
            ),
            expected.map { it.title },
        )
        assertEquals(
            listOf(
                "7x7i30e10m2d0", "7x7i30e10m2d1", "7x7i30e10m2d2",
                "10x10i30e10m2d0", "10x10i30e10m2d1", "10x10i30e10m2d2",
                "15x15i30e10m2d0", "15x15i30e10m2d1", "15x15i30e10m2d2",
            ),
            expected.map { it.params },
        )
    }

    @Test
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/bridges/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("bridges", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(10, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, seed))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("bridges", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in Bridges.presets().map { it.params }) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("bridges", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        for (params in listOf(
            "7x7i30e10m2d0", "7x7i30e10m2d1", "7x7i30e10m2d2",
            "10x10i30e10m2d0", "10x10i30e10m2d1",
            "15x15i30e10m2d0",
        )) {
            for (seed in 0..8) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("bridges", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "7",
            "7x7",
            "7x7m3",
            "7x7L",
            "7x7i30e10m2d1",
            " 7x7",
            "+7x7",
            "7X7",
            "7x7junk",
            "08x09",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("bridges", params, "12345").id,
                id(params, "12345"),
            )
        }

        for (params in listOf("7x7i0", "7x7i31", "7x7e101", "7x7m0", "7x7m5",
            "2x2", "7", "0x10")) {
            val expected = try {
                oracle.newGame("bridges", params, "12345").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Bridges, params, "12345")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        // The accepted boundary is not generated: the area check only.
        assertEquals(
            null,
            Bridges.validateParams(Bridges.Params(46340, 46340, 30, 10, 2, true, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Bridges.validateParams(Bridges.Params(46341, 46341, 30, 10, 2, true, 0), true),
        )
    }

    @Test
    fun `the generated aux matches the oracle auxinfo`() = OracleClient().use { oracle ->
        for (params in listOf("7x7i30e10m2d0", "7x7i30e10m2d1", "10x10i30e10m2d0")) {
            for (seed in listOf("1", "12345", "99999")) {
                val expected = oracle.newGame("bridges", params, seed)
                val generated = generated(params, seed)
                assertEquals(
                    "'$params' seed '$seed' desc",
                    expected.id.substringAfter(':'),
                    generated.text,
                )
                val kotlinSave = Midend.newGame(Bridges, params, seed).serialise()
                assertEquals(
                    "'$params' seed '$seed' auxinfo",
                    SgtpSave.parse(expected.state).value("AUXINFO"),
                    kotlinSave.value("AUXINFO"),
                )
                assertEquals('S', generated.auxInfo?.first())
                assertTrue(generated.auxInfo!!.contains("M"))
                assertTrue(generated.auxInfo!!.contains("L"))
            }
        }
    }

    @Test
    fun `the whole generated save retains the params split`() = OracleClient().use { oracle ->
        val save = Midend.newGame(Bridges, "7x7i30e10m2d1", "12345").serialise()
        assertEquals("7x7i30e10m2d0", save.value("PARAMS"))
        assertEquals("7x7i30e10m2d1", save.value("CPARAMS"))
        assertEquals(
            "7x7m2",
            Bridges.encodeParams(Bridges.decodeParams(save.value("PARAMS")!!), false),
        )
        assertEquals("a3b1e2b32g3a4a4a4a1i2a2b4b3", save.value("DESC"))
        assertTrue(save.value("AUXINFO")!!.isNotEmpty())
        assertEquals("1", save.value("NSTATES"))
        assertEquals("1", save.value("STATEPOS"))
        assertEquals(
            listOf("SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS", "SEED",
                "DESC", "AUXINFO", "NSTATES", "STATEPOS"),
            save.records.map { it.key },
        )
        // The oracle reads the Kotlin save and reports the same id.
        val oracleState = oracle.id(save.serialise())
        assertEquals(Midend.newGame(Bridges, "7x7i30e10m2d1", "12345").id(), oracleState.id)
    }

    @Test
    fun `retry fixtures match the oracle`() = OracleClient().use { oracle ->
        for ((seed, desc) in listOf(
            "4" to "a1a3b2g1b2a1b4d43g4a2c4a4c3",
            "31" to "3b3b1a1t3a3j2e2",
        )) {
            val actual = id("7x7i30e10m2d1", seed)
            assertEquals("seed $seed desc", desc, actual.substringAfter(':'))
            assertEquals("seed $seed oracle", oracle.newGame("bridges", "7x7i30e10m2d1", seed).id, actual)
        }
    }

    @Test
    fun `generation is deterministic`() {
        for (params in Bridges.presets().map { it.params }) {
            val first = generated(params, "12345")
            val second = generated(params, "12345")
            assertEquals("'$params' text", first.text, second.text)
            assertEquals("'$params' aux", first.auxInfo, second.auxInfo)
        }
        val randomA = RandomState.fromSeed("12345")
        val randomB = RandomState.fromSeed("12345")
        generated("7x7i30e10m2d1", "12345")
        Bridges.newDesc(Bridges.decodeParams("7x7i30e10m2d1"), randomA)
        Bridges.newDesc(Bridges.decodeParams("7x7i30e10m2d1"), randomB)
        assertEquals(randomA.encodeState(), randomB.encodeState())
    }
}
