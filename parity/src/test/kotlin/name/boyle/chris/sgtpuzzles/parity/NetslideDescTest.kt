package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: netslide.
 *
 * Parameters, presets, both encodings, validation, and
 * `params + seed -> DESC + AUXINFO`.
 *
 * The generator draws a sorted index from a changing `tree234`
 * frontier, then two values per shuffle attempt, then one value per
 * barrier. Any extra or missing draw changes every description, so the
 * seed matrix is the main guard on the RNG order.
 */
class NetslideDescTest {

    @Test
    fun `parameters decode and encode against the oracle`() = OracleClient().use { oracle ->
        for (text in PARAM_CASES) {
            val expected = oracle.newGame("netslide", text, SEED)
            val actual = Midend.newGame(Netslide, text, SEED)
            assertEquals("id '$text'", expected.id, actual.id())

            val save = actual.serialise()
            val expectedSave = SgtpSave.parse(expected.state)
            assertEquals(
                "PARAMS '$text'",
                expectedSave.value("PARAMS"),
                save.value("PARAMS"),
            )
            assertEquals(
                "CPARAMS '$text'",
                expectedSave.value("CPARAMS"),
                save.value("CPARAMS"),
            )
            assertEquals(
                "short form '$text'",
                expected.id.substringBefore(':'),
                Netslide.encodeParams(Netslide.decodeParams(text), false),
            )
            assertEquals(
                "full form '$text'",
                expectedSave.value("CPARAMS"),
                Netslide.encodeParams(Netslide.decodeParams(text), true),
            )
        }
    }

    @Test
    fun `the short form drops the barrier rate`() {
        val easy = Netslide.decodeParams("3x3b1")
        assertEquals("3x3", Netslide.encodeParams(easy, false))
        assertEquals("3x3b1", Netslide.encodeParams(easy, true))

        val wrapping = Netslide.decodeParams("3x3wm2")
        assertEquals("3x3wm2", Netslide.encodeParams(wrapping, false))
        assertEquals("3x3wm2", Netslide.encodeParams(wrapping, true))

        val fractional = Netslide.decodeParams("4x4b0.5")
        assertEquals("4x4", Netslide.encodeParams(fractional, false))
        assertEquals("4x4b0.5", Netslide.encodeParams(fractional, true))

        assertEquals("0.1", Netslide.encodeParams(Netslide.decodeParams("3x3b1e-1"), true)
            .removePrefix("3x3b"))
        assertEquals("0.9999", Netslide.encodeParams(Netslide.decodeParams("3x3b0.9999"), true)
            .removePrefix("3x3b"))
        assertEquals("0.25", Netslide.encodeParams(Netslide.decodeParams("3x3b0.25"), true)
            .removePrefix("3x3b"))
    }

    @Test
    fun `validation messages match the oracle`() = OracleClient().use { oracle ->
        for (text in INVALID_CASES) {
            val expected = try {
                oracle.newGame("netslide", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            val params = Netslide.decodeParams(text)
            assertEquals("error for '$text'", expected, Netslide.validateParams(params, true))
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Netslide.validateParams(params, false),
            )
        }

        // `2x2` is the smallest accepted board.
        assertNull(Netslide.validateParams(Netslide.decodeParams("2x2"), true))
        // The largest accepted square board. Do not generate it: the
        // two arrays need many gigabytes.
        assertNull(Netslide.validateParams(Netslide.decodeParams("46340x46340"), true))
        assertNotEquals(
            null,
            Netslide.validateParams(Netslide.decodeParams("46341x46341"), true),
        )
    }

    /**
     * The intentional deviation: C accepts a NaN barrier rate in
     * `validate_params` and then aborts at `netslide.c:629`. The port
     * returns a clear error instead. The entry belongs in
     * `docs/DEVIATIONS.md`.
     */
    @Test
    fun `a NaN barrier rate is rejected instead of aborting`() {
        val params = Netslide.decodeParams("3x3bnan") as Netslide.Params
        assertEquals("Barrier probability must be a number", Netslide.validateParams(params, true))
        assertTrue(params.barrierProbability.isNaN())
        val error = try {
            Midend.newGame(Netslide, "3x3bnan", SEED)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        assertEquals("Barrier probability must be a number", error)
    }

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("netslide")
        val actual = Netslide.presets()

        assertEquals("preset count", 9, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        // The short form would drop `b`, so the strings are stored
        // verbatim and the Easy presets keep their barriers.
        assertEquals("3x3b1", actual[0].params)
        assertEquals("3x3w", actual[2].params)
    }

    @Test
    fun `descriptions and aux answers match the oracle for every model case`() =
        OracleClient().use { oracle ->
            for (params in DESC_PARAMS) {
                for (seed in SEEDS) {
                    val expected = oracle.newGame("netslide", params, seed)
                    val actual = Midend.newGame(Netslide, params, seed)
                    assertEquals("id netslide '$params' seed=$seed", expected.id, actual.id())
                    assertEquals(
                        "save netslide '$params' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                }
            }
        }

    @Test
    fun `the aux answer is the unshuffled board`() = OracleClient().use { oracle ->
        val easy = Midend.newGame(Netslide, "3x3b1", SEED)
        assertEquals("S15c9d6234", easy.auxInfo)
        assertEquals("3x3b1", easy.serialise().value("CPARAMS"))
        assertEquals("3x3", easy.serialise().value("PARAMS"))

        val medium = Midend.newGame(Netslide, "3x3", SEED)
        assertEquals("S15c9d6234", medium.auxInfo)

        // The AUXINFO record is the same in both, because the barriers
        // are placed after the tiles are saved.
        assertEquals(
            easy.serialise().value("AUXINFO"),
            medium.serialise().value("AUXINFO"),
        )
        assertEquals(
            oracle.newGame("netslide", "3x3b1", SEED).state,
            easy.serialise().serialise(),
        )

        for ((params, seed) in listOf(
            "2x2" to SEED,
            "4x4" to SEED,
            "5x5" to SEED,
            "4x7" to "another seed",
        )) {
            val midend = Midend.newGame(Netslide, params, seed)
            val current = midend.currentParams as Netslide.Params
            val area = current.width * current.height
            val aux = midend.auxInfo
            assertEquals("aux length for $params", area + 1, aux?.length)
            assertEquals("aux starts with S for $params", 'S', aux?.first())
            assertEquals(
                "the aux record matches the oracle for $params",
                SgtpSave.parse(oracle.newGame("netslide", params, seed).state).value("AUXINFO"),
                midend.serialise().value("AUXINFO"),
            )
        }
    }

    @Test
    fun `the generator consumes the same number of draws`() {
        // Regression guard: the encoded generator state after generating
        // `3x3` at seed 12345 pins eight tree draws, twenty-eight
        // shuffle draws, and no barrier draws. The oracle comparison
        // above checks the same order independently.
        val random = RandomState.fromSeed(SEED)
        val desc = Netslide.newDesc(Netslide.decodeParams("3x3"), random)
        assertEquals("21c4d5963", desc.text)
        assertEquals("S15c9d6234", desc.auxInfo)
        assertEquals(
            "8db2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e890898" +
                "0d443c29c69b60c99b34fdf99eb47094a01b8087478253bec41cbe7f10",
            random.encodeState(),
        )
    }

    private companion object {
        const val SEED = "12345"

        val PARAM_CASES = listOf(
            "3",
            "3w",
            "3m5",
            "+3x3",
            " 3x3",
            "3 x 3",
            "03x03",
            "3x3x5",
            "3x3b",
            "3x3b0.0",
            "3x3b0",
            "3x3b0.5",
            "3x3b0.9999",
            "3x3b1e-1",
            "3x3b0.25",
            "3x3b1m5",
            "3x3m5b1",
            "3x3wm2",
            "3x3m0",
            "3x3b1",
            "3x3w",
            "3x3",
            "3x3b1e-1m5",
        )

        val INVALID_CASES = listOf(
            "1x3",
            "3x1",
            "0x0",
            "100000x100000",
            "3x3b-1",
            "3x3b1.5",
            "3x3binf",
            "3x3m-1",
            "46341x46341",
        )

        val SEEDS = listOf(
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

        /**
         * The preset matrix plus the model cases from the spec: a
         * fractional barrier rate, a target, a wrapping target, small
         * and non-square boards, and a rate that truncates to zero
         * barriers.
         */
        val DESC_PARAMS = listOf(
            "",
            "3x3b1",
            "3x3",
            "3x3w",
            "4x4b1",
            "4x4",
            "4x4w",
            "5x5b1",
            "5x5",
            "5x5w",
            "3x3b0.5",
            "3x3b0.1",
            "3x3b0.9999",
            "3x3b0.25",
            "3x3m5",
            "3x3m1",
            "3x3wm2",
            "4x4m10",
            "2x2",
            "2x2m1",
            "3x5",
            "5x3",
            "4x7",
        )
    }
}
