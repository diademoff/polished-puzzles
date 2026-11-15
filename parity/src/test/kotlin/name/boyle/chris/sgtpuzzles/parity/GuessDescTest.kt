package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: guess.
 *
 * Parameters, presets, the two flag letters, the decode and validate
 * quirks, `params + seed -> DESC`, and the retry rule of the
 * no-multiple generator.
 *
 * The no-multiple cases are the RNG-order trap: a duplicate draw is
 * discarded and the same peg is drawn again. The tests compare the exact
 * id and the generator state after the draws, so a reseed or an extra
 * draw fails even when the visible pegs happen to match.
 */
class GuessDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("guess")
        val actual = Guess.presets()
        assertEquals(2, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("Standard", actual[0].title)
        assertEquals("c6p4g10Bm", actual[0].params)
        assertEquals("Super", actual[1].title)
        assertEquals("c8p5g12Bm", actual[1].params)

        val defaults = oracle.newGame("guess", "", SEED)
        val midend = Midend.newGame(Guess, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals("c6p4g10Bm", Guess.encodeParams(midend.params, true))
        assertEquals("c6p4g10Bm", Guess.encodeParams(midend.params, false))
    }

    @Test
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/guess/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("guess", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 3, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                assertEquals("golden '$params'", id, Midend.newGame(Guess, params, seed).id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("guess", params, seed).id,
                    Midend.newGame(Guess, params, seed).id(),
                )
            }
        }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = Guess.presets().map { it.params }.distinct()
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("guess", params, seed).id,
                    Midend.newGame(Guess, params, seed).id(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep pins the generator order`() = OracleClient().use { oracle ->
        val paramsList = listOf(
            "c6p4g10Bm",
            "c8p5g12Bm",
            "c2p2g1Bm",
            "c3p2g1bm",
            "c10p10g1bm",
            "c4p4g10BM",
            "c3p3g2BM",
            "c10p2g3bm",
        )
        for (params in paramsList) {
            for (seed in 0..19) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("guess", params, seed.toString()).id,
                    Midend.newGame(Guess, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `no-multiple retries match the oracle draw for draw`() =
        OracleClient().use { oracle ->
            // Each triple is a params, a seed, and the exact number of
            // random_upto calls the generator makes. The duplicate
            // attempts are the retries: 19 for c4p4g10BM seed 9, which
            // is the worst case in the first 20 seeds of that preset.
            val cases = listOf(
                Triple("c4p4g10BM", "9", 23),
                Triple("c4p4g10BM", "4", 11),
                Triple("c4p4g10BM", "12345", 8),
                Triple("c3p3g2BM", "8", 12),
                Triple("c3p3g2BM", "7", 11),
                Triple("c3p2g1BM", "2", 6),
                Triple("c3p2g1BM", "0", 4),
                // The multiple path always draws exactly npegs times.
                Triple("c6p4g10Bm", "12345", 4),
                Triple("c10p10g1bm", "0", 10),
            )
            for ((params, seed, draws) in cases) {
                val p = Guess.decodeParams(params) as Guess.Params
                val random = RandomState.fromSeed(seed)
                val desc = Guess.newDesc(p, random).text
                val oracleStream = oracle.randomUpto(
                    p.ncolours.toLong(),
                    draws,
                    seed = seed,
                )
                assertEquals(
                    "$params '$seed' description",
                    oracle.newGame("guess", params, seed).id.substringAfter(':'),
                    desc,
                )
                // The generator state after the description proves that
                // no extra draw happened and that a retry continued the
                // same stream.
                assertEquals(
                    "$params '$seed' generator state",
                    oracleStream.state,
                    random.encodeState(),
                )
            }

            // The explicit ids of the retry fixtures, with both
            // presets and the 10-colour case.
            assertEquals(
                "c3p2g1bm:3ea7",
                Midend.newGame(Guess, "c3p2g1bm", "0").id(),
            )
            assertEquals(
                "c3p3g2BM:597268",
                Midend.newGame(Guess, "c3p3g2BM", "3").id(),
            )
            assertEquals(
                "c4p4g10BM:5180d9ff",
                Midend.newGame(Guess, "c4p4g10BM", "1").id(),
            )
            assertEquals(
                "c3p2g1bm:c1ac",
                Midend.newGame(Guess, "c3p2g1bm", "4").id(),
            )
        }

    @Test
    fun `decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "",
            "c6p4g10Bm",
            "p4c6g10",
            "c6x4g10Bm",
            "c6p4g10Xm",
            "c6p4g10B",
            "c6p4g10m",
            "c6p4g10bm",
            "C6P4G10BM",
            "6x4",
            "c+6p+4g+10Bm",
            "c6p4g10Bm ",
        )
        for (text in accepted) {
            assertEquals(
                "id for '$text'",
                oracle.newGame("guess", text, SEED).id,
                Midend.newGame(Guess, text, SEED).id(),
            )
        }

        for (text in listOf("c-2p4g10Bm", "c1p4g10Bm", "c11p4g10Bm", "c6p4g0Bm", "c3p4g10BM")) {
            val expected = try {
                oracle.newGame("guess", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "message for '$text'",
                expected,
                Guess.validateParams(Guess.decodeParams(text), true),
            )
        }

        // The short and the full form are identical, so a game ID always
        // keeps both flag letters.
        val upper = oracle.newGame("guess", "C6P4G10BM", SEED)
        assertEquals("c6p4g10BM", upper.id.substringBefore(':'))
        assertEquals(
            "c6p4g10BM",
            Midend.newGame(Guess, "C6P4G10BM", SEED).id().substringBefore(':'),
        )
    }

    @Test
    fun `the flags appear in the id and in both save records`() =
        OracleClient().use { oracle ->
            for (blank in listOf(false, true)) {
                for (multiple in listOf(false, true)) {
                    val text = "c4p4g10" + (if (blank) "b" else "B") +
                        (if (multiple) "m" else "M")
                    val midend = Midend.newGame(Guess, text, SEED)
                    val save = midend.serialise()
                    assertEquals("$text PARAMS", text, save.value("PARAMS"))
                    assertEquals("$text CPARAMS", text, save.value("CPARAMS"))
                    assertTrue("$text id", midend.id().startsWith("$text:"))
                    assertEquals(
                        "$text oracle save",
                        save.serialise(),
                        oracle.newGame("guess", text, SEED).state,
                    )
                    assertEquals(
                        "$text oracle id",
                        oracle.newGame("guess", text, SEED).id,
                        midend.id(),
                    )
                }
            }
        }

    @Test
    fun `validate desc matches the oracle wrapper and the raw messages`() =
        OracleClient().use { oracle ->
            val valid = listOf("3ea7", "E432", "c1ac", "3f07")
            for (desc in valid) {
                val save = saveText("c3p2g1bm", desc)
                val expected = oracle.id(save)
                assertNull("'$desc' must be valid", Guess.validateDesc(
                    Guess.decodeParams("c3p2g1bm"),
                    desc,
                ))
                val midend = Midend.fromSave(Guess, SgtpSave.parse(save))
                assertEquals("'$desc' id", expected.id, midend.id())
            }

            val invalid = listOf("abc", "3ea", "3ea7ff", "zzzz", "0000")
            for (desc in invalid) {
                val save = saveText("c3p2g1bm", desc)
                val expected = try {
                    oracle.id(save)
                    null
                } catch (e: OracleException) {
                    e.message
                }
                // The midend wraps the game message in the save loader.
                assertEquals(
                    "'$desc' oracle message",
                    "Game description in save file is invalid",
                    expected,
                )
                try {
                    Midend.fromSave(Guess, SgtpSave.parse(save))
                    throw AssertionError("Kotlin loaded '$desc'")
                } catch (e: IllegalStateException) {
                    assertTrue(
                        "'$desc' Kotlin message: ${e.message}",
                        e.message!!.startsWith("game description: "),
                    )
                }
            }

            // A generated description always re-validates.
            for (params in Guess.presets().map { it.params } + "c3p2g1BM") {
                val midend = Midend.newGame(Guess, params, SEED)
                assertNull(params, Guess.validateDesc(midend.currentParams, midend.desc))
            }
        }

    private fun saveText(params: String, desc: String): String = SgtpSave.of(
        listOf(
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("VERSION", SgtpSave.VERSION),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("GAME", Guess.name),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("PARAMS", params),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("CPARAMS", params),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("DESC", desc),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("NSTATES", "1"),
            name.boyle.chris.sgtpuzzles.engine.SaveRecord("STATEPOS", "1"),
        ),
    ).serialise()

    private companion object {
        const val SEED = "12345"
    }
}
