package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: lightup.
 *
 * Parameters, presets, validation, the decode quirks, the
 * `WxHb<bp>s<sym>d<diff>` encoding, and `params + seed -> DESC`.
 *
 * The generator is gated by the deduction solver twice per attempt, so
 * one seed can hide a solver mistake. The matrix covers all nine presets
 * with the golden seed, nine more seed strings, a numeric sweep, and the
 * black-percentage ramp.
 */
class LightupDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("lightup")
        val actual = Lightup.presets()

        assertEquals("preset count", 9, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }

        assertEquals(
            listOf(
                "7x7 Easy", "7x7 Tricky", "7x7 Hard",
                "10x10 Easy", "10x10 Tricky", "10x10 Hard",
                "14x14 Easy", "14x14 Tricky", "14x14 Hard",
            ),
            actual.map { it.title },
        )
        assertEquals(
            listOf(
                "7x7b20s3d0", "7x7b20s3d1", "7x7b20s3d2",
                "10x10b20s2d0", "10x10b20s2d1", "10x10b20s2d2",
                "14x14b20s2d0", "14x14b20s2d1", "14x14b20s2d2",
            ),
            actual.map { it.params },
        )

        val default = Lightup.defaultParams() as Lightup.Params
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), default)
        assertEquals("7x7b20s3d0", Lightup.encodeParams(default, true))
        assertEquals("7x7", Lightup.encodeParams(default, false))
        assertEquals("Light Up", Lightup.name)
        assertEquals("lightup", Lightup.backendName)
        assertEquals(32, Lightup.preferredTileSize)
        assertTrue(Lightup.canSolve)
    }

    @Test
    fun `the full and short encodings split the save parameters`() {
        val params = Lightup.Params(10, 10, 35, 2, 2)
        assertEquals("10x10b35s2d2", Lightup.encodeParams(params, true))
        assertEquals("10x10", Lightup.encodeParams(params, false))

        // A seed id decodes the short form over the default preset, so
        // `PARAMS` is always `...b20s3d0` when the size is 7x7. The
        // `s`/`d` split is the save format for every other preset.
        assertEquals(
            Lightup.Params(7, 7, 20, 3, 0),
            Lightup.decodeParams("7x7"),
        )
        assertEquals(
            Lightup.Params(10, 10, 20, 3, 0),
            Lightup.decodeParams("10x10"),
        )
        assertEquals(
            Lightup.Params(14, 14, 20, 3, 0),
            Lightup.decodeParams("14x14"),
        )

        for (preset in Lightup.presets()) {
            val midend = Midend.newGame(Lightup, preset.params, "12345")
            val save = midend.serialise().serialise()
            val parsed = name.boyle.chris.sgtpuzzles.engine.SgtpSave.parse(save)
            assertEquals(
                "${preset.params} PARAMS",
                Lightup.encodeParams(
                    Lightup.decodeParams(preset.params.substringBefore('b')),
                    true,
                ),
                parsed.value("PARAMS"),
            )
            assertEquals("${preset.params} CPARAMS", preset.params, parsed.value("CPARAMS"))
            assertTrue("${preset.params} id has no b/s/d", midend.id().substringBefore(':').matches(Regex("\\d+x\\d+")))
        }
    }

    @Test
    fun `decode quirks match the oracle`() = OracleClient().use { oracle ->
        // Every row is a real `decode_params` quirk. The full id is the
        // evidence: it carries the short parameters and the description.
        val cases = listOf(
            "7x7", " 5x5", "+7x7", "7X7", "7x7x7", "5x5s",
            "5x5b20s0s2d0", "5x5d", "5x5b20s0d0d2", "5x5b20s0r",
            "5x5b20s0rd1", "18x10", "7x7b20s3d0junk", "7x7 ",
        )
        for (text in cases) {
            assertEquals(
                "id '$text'",
                oracle.newGame("lightup", text, SEED).id,
                Midend.newGame(Lightup, text, SEED).id(),
            )
        }

        // The pinned decodes of the specification.
        assertEquals(Lightup.Params(5, 7, 20, 3, 0), Lightup.decodeParams(" 5x5"))
        assertEquals(Lightup.Params(7, 7, 20, 3, 0), Lightup.decodeParams("+7x7"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 0), Lightup.decodeParams("5x5s"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 0), Lightup.decodeParams("5x5b20s0s2d0"))
        assertEquals(Lightup.Params(5, 5, 20, 3, 0), Lightup.decodeParams("5x5d"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 0), Lightup.decodeParams("5x5b20s0d0d2"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 2), Lightup.decodeParams("5x5b20s0r"))
        assertEquals(Lightup.Params(5, 5, 20, 0, 1), Lightup.decodeParams("5x5b20s0rd1"))
        assertEquals(Lightup.Params(18, 10, 20, 3, 0), Lightup.decodeParams("18x10"))

        // A marker with no digits is `atoi("") == 0`, so `b` alone is
        // rejected by the percentage check.
        assertEquals(0, (Lightup.decodeParams("5x5b") as Lightup.Params).blackpc)
    }

    @Test
    fun `validation messages match the oracle in order`() = OracleClient().use { oracle ->
        val cases = listOf(
            "2x2", "2x3b20s4d0", "9x7b60s4d0", "7x7b4s3d0", "7x7b101s3d0",
            "7x7b20s5d0", "7x7b20s3d3", "46341x46341",
        )
        for (text in cases) {
            val expected = validationError(oracle, text)
            assertNotNull("'$text' must be rejected by C", expected)
            assertEquals(
                "error for '$text'",
                expected,
                Lightup.validateParams(Lightup.decodeParams(text), true),
            )
        }

        // C's order: small, area, percentage, symmetry, 4-way, type,
        // difficulty.
        assertEquals(
            "Width and height must be at least 2",
            Lightup.validateParams(Lightup.Params(1, 9, 20, 3, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Lightup.validateParams(Lightup.Params(65536, 65536, 20, 3, 0), true),
        )
        assertEquals(
            "Percentage of black squares must be between 5% and 100%",
            Lightup.validateParams(Lightup.Params(7, 7, 4, 3, 0), true),
        )
        assertEquals(
            "4-fold symmetry is only available with square grids",
            Lightup.validateParams(Lightup.Params(9, 7, 60, 4, 0), true),
        )
        assertEquals(
            "Width or height must be at least 3 for 4-way symmetry",
            Lightup.validateParams(Lightup.Params(2, 2, 20, 3, 0), true),
        )
        assertEquals(
            "Unknown symmetry type",
            Lightup.validateParams(Lightup.Params(7, 7, 20, 5, 0), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Lightup.validateParams(Lightup.Params(7, 7, 20, 3, 3), true),
        )

        // `9x7b60s3d0` is accepted: the non-square test names only
        // `SYMM_ROT4`, and the 3x3 test needs both sides below 3.
        assertNull(Lightup.validateParams(Lightup.Params(9, 7, 60, 3, 0), true))
        // The C area boundary: accepted by C, rejected by the port guard.
        assertEquals(
            "Width times height is too large for this build",
            Lightup.validateParams(Lightup.Params(46340, 46340, 20, 3, 0), true),
        )
        assertEquals(
            "Width times height is too large for this build",
            Lightup.validateParams(Lightup.Params(2000, 2000, 20, 0, 0), true),
        )
        assertEquals(
            "Width times height is too large for this build",
            Lightup.validateParams(Lightup.Params(2000, 2000, 20, 0, 0), false),
        )
        // The guard boundary is exact: 2^20 cells are accepted, one more
        // column is rejected.
        assertNull(Lightup.validateParams(Lightup.Params(1024, 1024, 20, 0, 0), true))
        assertNotNull(Lightup.validateParams(Lightup.Params(1024, 1025, 20, 0, 0), true))

        // D-006, second half: C accepts 100% black at difficulty 1 or 2
        // and then loops forever.
        assertEquals(
            "A board of 100% black squares cannot be generated at this difficulty",
            Lightup.validateParams(Lightup.Params(7, 7, 100, 3, 1), true),
        )
        assertEquals(
            "A board of 100% black squares cannot be generated at this difficulty",
            Lightup.validateParams(Lightup.Params(7, 7, 100, 2, 2), true),
        )
        // Difficulty 0 is fine: C returns an all-black description.
        assertNull(Lightup.validateParams(Lightup.Params(7, 7, 100, 3, 0), true))
    }

    @Test
    fun `validateDesc uses the exact messages`() = OracleClient().use { oracle ->
        val params = "3x3b20s0d0"
        val cases = listOf(
            "i", "h", "j", "S", "+", "ii", "aabbb", "BBBBBBBBB", "444444444",
            "a", "", "aB", "iii",
        )
        for (desc in cases) {
            val expectedError = descError(oracle, params, desc)
            val actualError = Lightup.validateDesc(Lightup.decodeParams(params), desc)
            assertEquals(
                "desc '$desc' acceptance",
                expectedError == null,
                actualError == null,
            )
            if (expectedError != null) {
                // A save load replaces the game's message with its own,
                // so pin the game message from `lightup.c:1652-1672`
                // separately below.
                assertTrue(
                    "desc '$desc' rejected as the game text",
                    actualError == "Game description shorter than expected" ||
                        actualError == "Game description longer than expected" ||
                        actualError == "Invalid character in game description",
                )
            }
        }

        assertNull(Lightup.validateDesc(Lightup.decodeParams(params), "i"))
        assertEquals(
            "Game description shorter than expected",
            Lightup.validateDesc(Lightup.decodeParams(params), "h"),
        )
        assertEquals(
            "Invalid character in game description",
            Lightup.validateDesc(Lightup.decodeParams(params), "S"),
        )
        assertEquals(
            "Game description longer than expected",
            Lightup.validateDesc(Lightup.decodeParams(params), "j"),
        )
        // A run may cross a row boundary; `p` is a run of 16 cells.
        assertNull(Lightup.validateDesc(Lightup.decodeParams("4x4b20s0d0"), "p"))
    }

    @Test(timeout = 180_000)
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (preset in Lightup.presets()) {
                for (seed in seeds) {
                    val expected = oracle.newGame("lightup", preset.params, seed)
                    val actual = Midend.newGame(Lightup, preset.params, seed)
                    assertEquals(
                        "id lightup '${preset.params}' seed=$seed",
                        expected.id,
                        actual.id(),
                    )
                    assertEquals(
                        "status lightup '${preset.params}' seed=$seed",
                        expected.status,
                        actual.status(),
                    )
                    assertEquals(
                        "state lightup '${preset.params}' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                    assertNull(
                        "auxinfo '${preset.params}' seed=$seed",
                        actual.auxInfo,
                    )
                    assertNull(
                        "desc '${preset.params}' seed=$seed",
                        Lightup.validateDesc(
                            Lightup.decodeParams(preset.params),
                            actual.desc,
                        ),
                    )
                }
            }
        }

    @Test(timeout = 180_000)
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            for (params in listOf("7x7b20s3d2", "10x10b20s2d1", "14x14b20s2d0")) {
                for (seed in 0..9) {
                    assertEquals(
                        "lightup '$params' seed=$seed",
                        oracle.newGame("lightup", params, seed.toString()).id,
                        Midend.newGame(Lightup, params, seed.toString()).id(),
                    )
                }
            }
        }

    @Test
    fun `the golden descriptions are pinned`() {
        val golden = Json.parse(File(testdata, "golden/lightup/desc.json").readText())
            as JsonValue.Obj
        val entries = (golden.field("entries") as JsonValue.Arr).items
        var checked = 0
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            assertEquals("golden '$params'", id, Midend.newGame(Lightup, params, SEED).id())
            checked++
        }
        assertEquals(10, checked)
    }

    @Test
    fun `the black-percentage ramp is part of the stream`() = OracleClient().use { oracle ->
        // With blackpc = 5 on a 3x3 board, nblack is 0 or 1 and every
        // attempt fails the uniqueness test, so the generator must raise
        // the percentage and keep the once-built cell shuffle.
        val cases = listOf(
            Triple("3x3b5s0d0", "1", "3x3:2h"),
            Triple("4x4b5s0d0", "1", "4x4:b1l2"),
            Triple("3x3b5s1d0", "1", "3x3:Be2b"),
        )
        for ((params, seed, expected) in cases) {
            assertEquals(
                "oracle $params seed $seed",
                expected,
                oracle.newGame("lightup", params, seed).id,
            )
            assertEquals(
                "port $params seed $seed",
                expected,
                Midend.newGame(Lightup, params, seed).id(),
            )
        }

        // `blackpc = 100` at difficulty 0 is the all-black board.
        assertEquals(
            "7x7:" + "B".repeat(49),
            Midend.newGame(Lightup, "7x7b100s0d0", "1").id(),
        )
    }

    private fun validationError(oracle: OracleClient, params: String): String? =
        try {
            oracle.newGame("lightup", params, SEED)
            null
        } catch (e: OracleException) {
            e.message
        }

    private fun descError(oracle: OracleClient, params: String, desc: String): String? {
        val text = name.boyle.chris.sgtpuzzles.engine.SgtpSave.of(
            listOf(
                name.boyle.chris.sgtpuzzles.engine.SaveRecord(
                    "SAVEFILE",
                    name.boyle.chris.sgtpuzzles.engine.SgtpSave.MAGIC,
                ),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord(
                    "VERSION",
                    name.boyle.chris.sgtpuzzles.engine.SgtpSave.VERSION,
                ),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord("GAME", Lightup.name),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord("PARAMS", params),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord("CPARAMS", params),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord("DESC", desc),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord("NSTATES", "1"),
                name.boyle.chris.sgtpuzzles.engine.SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()
        return try {
            oracle.id(text)
            null
        } catch (e: OracleException) {
            e.message
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
