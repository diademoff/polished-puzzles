package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: inertia.
 *
 * Parameters, presets, the decode quirks, the validation order, and
 * `params + seed -> DESC` for every preset, a list of named seeds, and
 * a numeric sweep. The generator attempt counts are pinned from a C
 * probe built from the vendored `inertia.c` with one counter in
 * `gengrid`; the oracle protocol does not expose them.
 *
 * The 4x4 sweep of seeds 0..300 peaks at 3 attempts, so the
 * `maxdist_threshold` bump at 50 distance failures is not reached by
 * any realistic fixture. The bump is covered by inspection of the
 * ported counter and by the attempt counts here.
 */
class InertiaDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Inertia, params, seed).id()

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("inertia")
        val actual = Inertia.presets()

        assertEquals(3, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(listOf("10x8", "15x12", "20x16"), expected.map { it.title })
        assertEquals(listOf("10x8", "15x12", "20x16"), expected.map { it.params })
    }

    @Test
    fun `the frozen descriptions match the goldens`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/inertia/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("inertia", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(4, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, seed))
            assertEquals("oracle '$params'", oracle.newGame("inertia", params, seed).id, id(params, seed))
        }
    }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        // The empty seed is a real seed: SHA-1 of the empty string.
        val seeds = listOf(
            "1",
            "2",
            "3",
            "12345",
            "seed",
            "another seed",
            "0",
            "-7",
            "",
        )
        for (params in Inertia.presets().map { it.params }.distinct()) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("inertia", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        // 4x4 is the smallest board where `maxdist` can exceed the
        // initial threshold of 2; 3x3 covers the possgems path.
        for (params in listOf("2x3", "2x4", "3x3", "4x4", "5x5")) {
            for (seed in 0..39) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("inertia", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
        for (params in Inertia.presets().map { it.params }.distinct()) {
            for (seed in 0..9) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("inertia", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "10x8",
            "10",
            "10x8junk",
            "10x8x9",
            " 10x8",
            "+10x8",
            "2x3",
            "3x2",
            "2x4",
            "10x",
        )) {
            val expected = try {
                oracle.newGame("inertia", params, "12345").id
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                id(params, "12345")
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }
    }

    @Test
    fun `validation messages match the oracle in order`() = OracleClient().use { oracle ->
        for (params in listOf(
            "1x8",
            "8x1",
            "-1x8",
            "0x8",
            "10x0",
            "2x2",
            "65536x65536",
        )) {
            val expected = try {
                oracle.newGame("inertia", params, "1").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Inertia, params, "1")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        // An accepted minimum still generates.
        val twoByThree = oracle.newGame("inertia", "2x3", "12345").id
        assertEquals("2x3:wbgmSs", twoByThree)
        assertEquals(twoByThree, id("2x3", "12345"))
        val threeByTwo = oracle.newGame("inertia", "3x2", "12345").id
        assertEquals("3x2:wbgmSs", threeByTwo)
        assertEquals(threeByTwo, id("3x2", "12345"))
    }

    @Test
    fun `the huge area is accepted by validation but refused at generation`() =
        OracleClient().use { oracle ->
            // D-008. The oracle probe is not run: C allocates about
            // 86 GB and does not finish. Validation keeps the C result.
            assertEquals(
                null,
                Inertia.validateParams(Inertia.Params(46340, 46340), true),
            )
            assertEquals(
                null,
                Inertia.validateParams(Inertia.Params(46340, 46340), false),
            )
            assertEquals(
                "Width times height must not be unreasonably large",
                Inertia.validateParams(Inertia.Params(65536, 65536), true),
            )
            val error = try {
                Midend.newGame(Inertia, "46340x46340", "1")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("Width times height is too large for this build", error)

            // Every real preset is far below the guard.
            for (params in Inertia.presets()) {
                val generated = oracle.newGame("inertia", params.params, "1").id
                assertEquals(params.params, generated.substringBefore(':'))
            }
        }

    @Test
    fun `the generation attempt counts match the C counter probe`() {
        fun attempts(params: String, seed: String): Int =
            Inertia.newDescWithAttempts(
                Inertia.decodeParams(params),
                RandomState.fromSeed(seed),
            ).attempts

        assertEquals(2, attempts("10x8", "12345"))
        assertEquals(15, attempts("15x12", "another seed"))
        assertEquals(12, attempts("20x16", "0"))
        assertEquals(2, attempts("2x3", "1"))
        assertEquals(6, attempts("5x5", "119"))
        assertEquals(3, attempts("4x4", "58"))
        assertEquals(1, attempts("3x3", "12345"))
    }
}
