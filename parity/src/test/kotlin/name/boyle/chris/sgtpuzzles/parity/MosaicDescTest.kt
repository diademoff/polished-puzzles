package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: mosaic.
 *
 * Parameters, presets, the decode quirks, validation, and
 * `params + seed -> DESC`.
 *
 * The generator draws one bit per cell, shuffles the whole board for
 * the acceptance solve, then runs `hide_clues` with two more shuffles
 * and one solver call per candidate. Any wrong draw or a different
 * solver mark order changes almost every description, so the seeds
 * below cover the frozen golden, the nine fixed seeds, a numeric sweep,
 * and the three known retry fixtures.
 */
class MosaicDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("mosaic")
        val actual = Mosaic.presets()

        assertEquals("six presets", 6, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        // `mosaic.c` has no `SMALL_SCREEN` branch.
        assertEquals(
            listOf("Size: 3x3", "Size: 5x5", "Size: 10x10", "Size: 15x15", "Size: 25x25", "Size: 50x50"),
            actual.map { it.title },
        )
        assertEquals(
            listOf("3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0"),
            actual.map { it.params },
        )

        assertEquals(Mosaic.Params(10, 10, true), Mosaic.defaultParams())
        assertEquals(
            "the default is the third preset",
            Midend.newGame(Mosaic, "10x10", SEED).id(),
            Midend.newGame(Mosaic, "", SEED).id(),
        )
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        // Accepted forms are compared through the full id, so the
        // parameters and the description must both match.
        val accepted = listOf(
            "10",
            "10x10",
            "10X10",
            "+10x10",
            " 10x10",
            "010x010",
            "10x10x7",
            "10x10h1",
            "10x10h2",
            "10x10h",
            "10x10habc",
            "10x10h0",
        )
        for (text in accepted) {
            assertEquals(
                "id for '$text'",
                oracle.newGame("mosaic", text, SEED).id,
                Midend.newGame(Mosaic, text, SEED).id(),
            )
        }

        // The direct decode values.
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10X10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("+10x10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams(" 10x10"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("010x010"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10x10x7"))
        assertEquals(Mosaic.Params(10, 10, false), Mosaic.decodeParams("10x10h0"))
        assertEquals(Mosaic.Params(10, 10, false), Mosaic.decodeParams("10x10h"))
        assertEquals(Mosaic.Params(10, 10, true), Mosaic.decodeParams("10x10h2"))
        assertEquals(Mosaic.Params(10, 0, true), Mosaic.decodeParams("10x"))
        assertEquals(Mosaic.Params(0, 0, true), Mosaic.decodeParams(""))

        // A non-aggressive game id has no `h`, so it decodes back to
        // aggressive. The current parameters keep the flag.
        val calm = Midend.newGame(Mosaic, "50x50h0", SEED)
        assertTrue("a short id is aggressive", (calm.params as Mosaic.Params).aggressive)
        assertFalse("the current parameters are calm", (calm.currentParams as Mosaic.Params).aggressive)
        assertFalse("the id has no h", calm.id().substringBefore(':').contains('h'))
        assertTrue("the full encoding keeps h0", Mosaic.encodeParams(calm.currentParams, true) == "50x50h0")

        // PARAMS drops the flag, CPARAMS keeps it.
        val save = SgtpSave.parse(oracle.newGame("mosaic", "50x50h0", SEED).state)
        assertEquals("PARAMS", "50x50", save.value("PARAMS"))
        assertEquals("CPARAMS", "50x50h0", save.value("CPARAMS"))
        val kotlinSave = SgtpSave.parse(calm.serialise().serialise())
        assertEquals("Kotlin PARAMS", "50x50", kotlinSave.value("PARAMS"))
        assertEquals("Kotlin CPARAMS", "50x50h0", kotlinSave.value("CPARAMS"))
        assertEquals("PARAMS == CPARAMS for 3x3", "3x3", Mosaic.encodeParams(Mosaic.decodeParams("3x3"), true))
        assertEquals("PARAMS == CPARAMS for 3x3", "3x3", Mosaic.encodeParams(Mosaic.decodeParams("3x3"), false))
    }

    @Test
    fun `validation messages match the oracle in C order`() = OracleClient().use { oracle ->
        val cases = listOf(
            "10x",
            "2x3",
            "3x2",
            "0x0",
            "-3x5",
            "10000x1",
            "1x10000",
            "101x100",
            "100x101",
            "46340x4",
        )
        for (text in cases) {
            val expected = try {
                oracle.newGame("mosaic", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "full error for '$text'",
                expected,
                Mosaic.validateParams(Mosaic.decodeParams(text), true),
            )
        }

        assertNull("100x100", Mosaic.validateParams(Mosaic.Params(100, 100, true), true))
        assertNull("3x3333", Mosaic.validateParams(Mosaic.Params(3, 3333, true), true))
        assertEquals(
            "Maximum size is 10000 tiles",
            Mosaic.validateParams(Mosaic.Params(3, 3334, true), true),
        )
        assertEquals(
            "Minimal size is 3x3",
            Mosaic.validateParams(Mosaic.Params(2, 10000, true), true),
        )
        assertEquals(
            "Minimal size is 3x3",
            Mosaic.validateParams(Mosaic.Params(10000, 1, true), true),
        )
        // `full` is ignored by validate_params.
        assertEquals(
            "Minimal size is 3x3",
            Mosaic.validateParams(Mosaic.Params(2, 2, true), false),
        )
    }

    @Test
    fun `the frozen golden and every fixed seed match the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/mosaic/desc.json").readText(),
            ) as JsonValue.Obj
            val goldenEntries = (golden.field("entries") as JsonValue.Arr).items.map {
                it as JsonValue.Obj
            }
            assertEquals("seven golden entries", 7, goldenEntries.size)
            for (entry in goldenEntries) {
                val params = entry.string("params")
                val id = entry.string("id")
                assertEquals("golden '$params'", id, Midend.newGame(Mosaic, params, SEED).id())
                assertEquals("oracle golden '$params'", id, oracle.newGame("mosaic", params, SEED).id)
            }

            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
            for (params in listOf("", "3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0")) {
                for (seed in seeds) {
                    val expected = oracle.newGame("mosaic", params, seed)
                    val actual = Midend.newGame(Mosaic, params, seed)
                    assertEquals("id range '$params' seed=$seed", expected.id, actual.id())
                    assertEquals("status '$params' seed=$seed", expected.status, actual.status())
                    assertEquals(
                        "state '$params' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                }
            }
        }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            for (params in listOf("3x3", "5x5", "10x10")) {
                for (seed in 0..19) {
                    assertEquals(
                        "mosaic '$params' seed=$seed",
                        oracle.newGame("mosaic", params, seed.toString()).id,
                        Midend.newGame(Mosaic, params, seed.toString()).id(),
                    )
                }
            }
            // One more seed for the large and the non-aggressive presets.
            for (params in listOf("15x15", "25x25", "50x50h0")) {
                assertEquals(
                    "mosaic '$params' seed=7",
                    oracle.newGame("mosaic", params, "7").id,
                    Midend.newGame(Mosaic, params, "7").id(),
                )
            }
        }

    @Test
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine MosaicTest pins the attempt counts: 3x3 seed 12345
        // needs 21 start-point and 2 acceptance retries, 5x5 seed 2
        // needs 23 and 7, and 10x10 seed 1 needs 18 and 28.
        val cases = listOf(
            Triple("3x3", "12345", "3x3:4b47d"),
            Triple("5x5", "2", "5x5:a34a2h663a4e5a"),
            Triple("10x10", "1", "10x10:2d6a2d4b6c31a2b42b5c312a3b3c3a2a6c433234b3a6a4b58c53a465e4a4d3a3e5a"),
        )
        for ((params, seed, id) in cases) {
            assertEquals("oracle retry '$params' seed=$seed", id, oracle.newGame("mosaic", params, seed).id)
            assertEquals("port retry '$params' seed=$seed", id, Midend.newGame(Mosaic, params, seed).id())
        }
    }

    @Test
    fun `the aggressive flag changes a board only when a clue is hidden`() =
        OracleClient().use { oracle ->
            // 3x3 seed 12345 hides a needed clue: 4b47d against 4a447d.
            assertEquals(
                "3x3:4b47d",
                Midend.newGame(Mosaic, "3x3", "12345").id(),
            )
            assertEquals(
                "3x3:4a447d",
                Midend.newGame(Mosaic, "3x3h0", "12345").id(),
            )
            assertEquals(
                oracle.newGame("mosaic", "3x3h0", "12345").id,
                Midend.newGame(Mosaic, "3x3h0", "12345").id(),
            )

            // Seeds 1-3 hide no needed clue, so the flag changes nothing.
            for (seed in listOf("1", "2", "3")) {
                assertEquals(
                    "seed $seed is the same",
                    Midend.newGame(Mosaic, "3x3", seed).id(),
                    Midend.newGame(Mosaic, "3x3h0", seed).id(),
                )
            }
        }

    @Test
    fun `newDesc never writes aux info`() {
        for (params in listOf("", "3x3", "5x5", "10x10", "15x15", "25x25", "50x50h0")) {
            val midend = Midend.newGame(Mosaic, params, SEED)
            assertNull("$params AUXINFO", midend.auxInfo)
            assertNull("$params has no AUXINFO record", midend.serialise().value("AUXINFO"))
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
