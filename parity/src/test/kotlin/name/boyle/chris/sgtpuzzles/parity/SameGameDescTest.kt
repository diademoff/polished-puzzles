package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SameGame
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: samegame.
 *
 * Parameters, validation, presets, and `params + seed -> DESC`. The
 * generator call order is the main risk: a skipped `random_upto`,
 * a changed shuffle, or a different retry loop changes the whole
 * description, so the seeds and parameter sets below are chosen to
 * exercise every branch.
 */
class SameGameDescTest {

    @Test
    fun `parameters round-trip and validate`() = OracleClient().use { oracle ->
        val cases = listOf(
            "5x5c3s2" to SameGame.Params(5, 5, 3, 2, true),
            "5x5" to SameGame.Params(5, 5, 3, 2, true),
            "10" to SameGame.Params(10, 10, 3, 2, true),
            "5x5c4s1r" to SameGame.Params(5, 5, 4, 1, false),
            "+5x5c3s2" to SameGame.Params(5, 5, 3, 2, true),
            " 5x5c3s2" to SameGame.Params(5, 5, 3, 2, true),
            "5X5" to SameGame.Params(5, 5, 3, 2, true),
            "5x5c3s2x" to SameGame.Params(5, 5, 3, 2, true),
            "5x5c3s2rxyz" to SameGame.Params(5, 5, 3, 2, false),
            "5x" to SameGame.Params(5, 0, 3, 2, true),
        )

        for ((text, expected) in cases) {
            assertEquals("decode '$text'", expected, SameGame.decodeParams(text))
            if (SameGame.validateParams(expected, true) == null) {
                assertEquals(
                    "id '$text'",
                    oracle.newGame("samegame", text, SEED).id,
                    Midend.newGame(SameGame, text, SEED).id(),
                )
            }
        }

        for (text in listOf("5x5c10s2", "5x", "0x5", "5x5c2s2", "1x1c3s2",
            "2x2c9s2r", "5x5c3s0", "5x5c3s3", "2x2c1s2r")
        ) {
            val params = SameGame.decodeParams(text)
            val expected = try {
                oracle.newGame("samegame", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("error for '$text'", expected, SameGame.validateParams(params, true))
            assertEquals(
                "error for '$text' (short form)",
                expected,
                SameGame.validateParams(params, false),
            )
        }
    }

    @Test
    fun `the short form drops solubility and the full form keeps it`() = OracleClient().use { oracle ->
        val params = SameGame.decodeParams("7x3c4s1r")
        assertEquals("7x3c4s1", SameGame.encodeParams(params, false))
        assertEquals("7x3c4s1r", SameGame.encodeParams(params, true))

        val expected = oracle.newGame("samegame", "7x3c4s1r", SEED)
        val actual = Midend.newGame(SameGame, "7x3c4s1r", SEED)
        assertEquals("save", expected.state, actual.serialise().serialise())
        assertEquals("id", expected.id, actual.id())

        val save = SgtpSave.parse(expected.state)
        assertEquals("PARAMS", "7x3c4s1", save.value("PARAMS"))
        assertEquals("CPARAMS", "7x3c4s1r", save.value("CPARAMS"))
        assertTrue("the id cannot carry solubility", !expected.id.startsWith("7x3c4s1r:"))
    }

    @Test
    fun `presets match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("samegame")
        val actual = SameGame.presets()
        assertEquals("preset count", 5, expected.size)
        assertEquals("preset count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        // SMALL_SCREEN changes nothing here, so the third preset is the
        // 15x10 board and not the SLOW_SYSTEM 10x10 board.
        assertEquals("15x10c3s2", actual[2].params)
    }

    @Test
    fun `descriptions match the oracle`() = OracleClient().use { oracle ->
        val paramsList = listOf(
            "",
            "5x5c3s2",
            "10x5c3s2",
            "15x10c3s2",
            "15x10c4s2",
            "20x15c4s2",
            "1x3c3s2",
            "3x1c3s2",
            "1x2c3s2",
            "2x1c3s2",
            "5x5c3s1",
            "5x5c3s2r",
            "20x15c9s2r",
            "20x15c9s2",
            "7x3c4s1r",
            "2x2c3s2",
        )
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "samegame '$params' seed=$seed",
                    oracle.newGame("samegame", params, seed).id,
                    Midend.newGame(SameGame, params, seed).id(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep does not hide an rng order error`() = OracleClient().use { oracle ->
        val sweeps = listOf(
            "5x5c3s2" to 0..29,
            "10x5c3s2" to 0..19,
            "15x10c3s2" to 0..9,
        )
        for ((params, seeds) in sweeps) {
            for (seed in seeds) {
                assertEquals(
                    "samegame '$params' seed=$seed",
                    oracle.newGame("samegame", params, seed.toString()).id,
                    Midend.newGame(SameGame, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `description validation matches the oracle`() = OracleClient().use { oracle ->
        val params = "2x2c3s2"
        val accepted = listOf("1,2,3,1", "0,0,0,0", "01,002,3,1")
        val rejected = listOf(
            "1,2,3",
            "1,2,3,",
            "1 2,3,1",
            "1,2,3,1x",
            "1,2,3,4",
            "10,2,3,1",
            "1,,2,3",
            "",
        )

        for (desc in accepted) {
            assertNull("Kotlin accepts '$desc'", SameGame.validateDesc(
                SameGame.decodeParams(params), desc,
            ))
            val save = saveText(params, desc)
            assertEquals("oracle accepts '$desc'", oracle.id(save).id, "$params:$desc")
        }

        for (desc in rejected) {
            assertTrue("Kotlin rejects '$desc'", SameGame.validateDesc(
                SameGame.decodeParams(params), desc,
            ) != null)
            val save = saveText(params, desc)
            try {
                oracle.id(save)
                throw AssertionError("the oracle accepted '$desc'")
            } catch (e: OracleException) {
                assertEquals(
                    "oracle error for '$desc'",
                    "Game description in save file is invalid",
                    e.message,
                )
            }
        }
    }

    private fun saveText(params: String, desc: String): String = SgtpSave.of(
        listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Same Game"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        ),
    ).serialise()

    private companion object {
        const val SEED = "12345"
    }
}
