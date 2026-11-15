package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Level: semantic. Game: sixteen.
 *
 * Parameters, validation messages, presets, and `params + seed -> DESC`.
 *
 * The plain generator draws `random_upto(i)` with `i` running from `w*h`
 * down to `stop+1`, so a changed draw count changes every description.
 * The target generator draws two values per attempt, including the
 * attempts the retry rule rejects, so `4x4m50` (56 attempts) is the
 * sharpest vector.
 */
class SixteenDescTest {

    @Test
    fun `parameters decode and encode against the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "4x4",
            "4",
            "04x04",
            "+4x4",
            " 4x4",
            "4x 4",
            "4,4",
            "4x4x4",
            "4e",
            "4x4 ",
            "4x4m",
            "4x4m0010",
            "4x4m+2",
            "4m10",
            "4m10x4",
            "4x4m10m3",
            "4x4m10x3",
            "4x4m0",
            "4x4m10",
        )

        for (text in cases) {
            val expected = oracle.newGame("sixteen", text, SEED)
            val actual = Midend.newGame(Sixteen, text, SEED)
            assertEquals("id '$text'", expected.id, actual.id())
            assertNull(
                "validation '$text'",
                Sixteen.validateParams(Sixteen.decodeParams(text), true),
            )
            // The short form is the same string for this game, because
            // `encode_params` ignores `full`.
            assertEquals(
                "short parameters '$text'",
                Sixteen.encodeParams(Sixteen.decodeParams(text), true),
                Sixteen.encodeParams(Sixteen.decodeParams(text), false),
            )
        }
    }

    @Test
    fun `validation messages match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "1x4",
            "0x0",
            "x4",
            "m10",
            "100000x100000",
            "65536x65536",
            "4x4m-1",
            "46341x46341",
        )

        for (text in cases) {
            val expected = try {
                oracle.newGame("sixteen", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "error for '$text'",
                expected,
                Sixteen.validateParams(Sixteen.decodeParams(text), true),
            )
            assertEquals(
                "error for '$text' (short form)",
                expected,
                Sixteen.validateParams(Sixteen.decodeParams(text), false),
            )
        }

        // The largest accepted square board. Do not build a state for it:
        // it needs more memory than the test JVM has.
        assertNull(Sixteen.validateParams(Sixteen.decodeParams("46340x46340"), true))
    }

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("sixteen")
        val actual = Sixteen.presets()

        assertEquals("preset count", 5, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        // There is no SMALL_SCREEN branch, so the Android list is the
        // desktop list.
        assertEquals("3x3", actual[0].title)
        assertEquals("5x5", actual[4].title)
    }

    @Test
    fun `descriptions match the oracle for every preset and seed`() =
        OracleClient().use { oracle ->
            val paramsList = Sixteen.presets().map { it.params } +
                listOf("", "4x4m50")
            val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

            for (params in paramsList) {
                for (seed in seeds) {
                    val expected = oracle.newGame("sixteen", params, seed)
                    val actual = Midend.newGame(Sixteen, params, seed)
                    assertEquals("id sixteen '$params' seed=$seed", expected.id, actual.id())
                    assertEquals(
                        "save sixteen '$params' seed=$seed",
                        expected.state,
                        actual.serialise().serialise(),
                    )
                }
            }
        }

    @Test
    fun `target descriptions match the oracle, rejected attempts included`() =
        OracleClient().use { oracle ->
            val paramsList = listOf(
                "4x4m1",
                "4x4m2",
                "4x4m3",
                "4x4m10",
                "4x4m11",
                "4x4m50",
                "5x5m7",
                "2x2m1",
                "3x5m4",
                "5x3m4",
            )
            for (params in paramsList) {
                for (seed in listOf("12345", "1", "seed")) {
                    assertEquals(
                        "id sixteen '$params' seed=$seed",
                        oracle.newGame("sixteen", params, seed).id,
                        Midend.newGame(Sixteen, params, seed).id(),
                    )
                }
            }

            // A generator that ignores the target cannot produce both.
            assertNotEquals(
                oracle.newGame("sixteen", "4x4m10", SEED).id,
                oracle.newGame("sixteen", "4x4m11", SEED).id,
            )
        }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() =
        OracleClient().use { oracle ->
            val sweeps = listOf(
                "4x4" to 0..29,
                "3x3" to 0..9,
                "5x5" to 0..9,
                "4x4m10" to 0..9,
                "5x4m6" to 0..9,
            )
            for ((params, seeds) in sweeps) {
                for (seed in seeds) {
                    assertEquals(
                        "sixteen '$params' seed=$seed",
                        oracle.newGame("sixteen", params, seed.toString()).id,
                        Midend.newGame(Sixteen, params, seed.toString()).id(),
                    )
                }
            }
        }

    @Test
    fun `rectangle rotations place by rank`() = OracleClient().use { oracle ->
        assertEquals(
            oracle.newGame("sixteen", "4x5", SEED).id.substringAfter(':'),
            oracle.newGame("sixteen", "5x4", SEED).id.substringAfter(':'),
        )
        assertEquals(
            oracle.newGame("sixteen", "4x5", SEED).id,
            Midend.newGame(Sixteen, "4x5", SEED).id(),
        )
    }

    @Test
    fun `a description with leading zeros is valid and keeps its text`() =
        OracleClient().use { oracle ->
            val plain = (1..16).joinToString(",")
            val padded = (1..16).joinToString(",") { "0$it" }
            val plainSave = saveText("4x4", plain)
            val paddedSave = saveText("4x4", padded)

            val expectedPlain = oracle.id(plainSave)
            val expectedPadded = oracle.id(paddedSave)
            assertEquals("4x4:$plain", expectedPlain.id)
            assertEquals("4x4:$padded", expectedPadded.id)
            assertNotEquals("the two ids name the same board", expectedPlain.id, expectedPadded.id)

            val actualPadded = Midend.fromSave(Sixteen, SgtpSave.parse(paddedSave))
            assertEquals(expectedPadded.id, actualPadded.id())
            assertEquals(oracle.text(paddedSave), actualPadded.textFormat())
            assertEquals(
                oracle.text(plainSave),
                actualPadded.textFormat(),
            )
        }

    private fun saveText(params: String, desc: String): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Sixteen"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
