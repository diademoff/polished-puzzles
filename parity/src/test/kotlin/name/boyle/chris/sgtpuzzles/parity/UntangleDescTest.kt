package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: untangle.
 *
 * The parameters, the presets, the `atoi` decode quirks, the two
 * validation messages, the two-pass description validator, and
 * `params + seed -> DESC + AUXINFO`.
 *
 * The generator draws one `w*h` shuffle and one or more `n` label
 * shuffles, so the seed sweep guards both the grid shuffle and the
 * acceptance loop. `AUXINFO` is compared through the save file, which
 * pins the double `sin`/`cos` in `make_circle`.
 */
class UntangleDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf("6", "10", "15", "20", "25")

    private val seeds = listOf(
        "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
    )

    @Test
    fun `identity, defaults, and presets match the C table`() {
        assertEquals("Untangle", Untangle.name)
        assertEquals("untangle", Untangle.backendName)
        assertEquals(64, Untangle.preferredTileSize)
        assertTrue(Untangle.canSolve)
        assertEquals(false, Untangle.wantsStatusBar)
        assertEquals(false, Untangle.canFormatAsText)

        assertEquals("10", Untangle.encodeParams(Untangle.defaultParams(), true))

        val expected = listOf(
            "6 points" to "6",
            "10 points" to "10",
            "15 points" to "15",
            "20 points" to "20",
            "25 points" to "25",
        )
        assertEquals(expected, Untangle.presets().map { it.title to it.params })
        for (preset in Untangle.presets()) {
            val params = Untangle.decodeParams(preset.params)
            assertEquals(preset.params, Untangle.encodeParams(params, true))
            assertEquals(preset.params, Untangle.encodeParams(params, false))
        }
    }

    @Test
    fun `presets match the oracle`() = OracleClient().use { oracle ->
        assertEquals(
            oracle.presets("untangle").map { it.title to it.params },
            Untangle.presets().map { it.title to it.params },
        )
    }

    @Test(timeout = 120_000)
    fun `parameter decoding follows atoi and validation`() = OracleClient().use { oracle ->
        // Valid forms: the oracle generates the same game.
        for (text in listOf("6", "10", "10x10", "10abc", " 10", "+10", "010")) {
            val expected = oracle.newGame("untangle", text, SEED)
            val actual = Midend.newGame(Untangle, text, SEED)
            assertEquals("id '$text'", expected.id, actual.id())
            assertEquals(
                "save '$text'",
                expected.state,
                actual.serialise().serialise(),
            )
        }

        // Invalid forms: the oracle returns validate_params' message.
        val invalid = listOf(
            "-5" to 0,
            "3" to 0,
            "4294967296" to 0,
            "2147483648" to 0,
            "99999999999999999999" to 0,
            "715827883" to 0,
        )
        for ((text, _) in invalid) {
            val error = try {
                oracle.newGame("untangle", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertNotNull("oracle rejects '$text'", error)
            assertEquals(
                "message '$text'",
                error,
                Untangle.validateParams(Untangle.decodeParams(text), true),
            )
        }

        assertEquals(0, (Untangle.decodeParams("") as Untangle.Params).n)
        assertEquals(
            "unreasonably large at the bound",
            "Number of points must not be unreasonably large",
            Untangle.validateParams(Untangle.Params(Int.MAX_VALUE / 3 + 1), true),
        )
        assertNull(
            "the accepted bound",
            Untangle.validateParams(Untangle.Params(Int.MAX_VALUE / 3), true),
        )
    }

    @Test
    fun `validate desc matches the C table`() = OracleClient().use { oracle ->
        val n = 10
        val cases = listOf(
            "" to null,
            "0-1" to null,
            "0-1," to null,
            "0-1,2-3" to null,
            "1-0" to null,
            "0-1,0-1" to null,
            "00000-0001" to null,
            "0-0" to "Node linked to itself in game description",
            "0-1,," to "Expected '-' after number in game description",
            "0-1x" to "Expected ',' after number in game description",
            "0" to "Expected '-' after number in game description",
            "5" to "Expected '-' after number in game description",
            "10-11" to "Number out of range in game description",
            "-1-0" to "Number out of range in game description",
            " 0-1" to "Expected '-' after number in game description",
            "+0-1" to "Expected '-' after number in game description",
            "3-3,4-5" to "Node linked to itself in game description",
        )

        for ((desc, message) in cases) {
            val params = Untangle.Params(n)
            assertEquals("message '$desc'", message, Untangle.validateDesc(params, desc))

            // The oracle masks the exact message, but it agrees on
            // acceptance. Re-reading a save with this description is
            // the only path that reaches validate_desc.
            val save = save(n, desc)
            val accepted = try {
                oracle.id(save)
                true
            } catch (e: OracleException) {
                false
            }
            assertEquals("oracle accepts '$desc'", message == null, accepted)

            if (message == null) {
                // The stored edge list is deduplicated and ordered, but
                // the id keeps the description verbatim.
                val loaded = Midend.fromSave(Untangle, SgtpSave.parse(save))
                assertEquals("id '$desc'", "10:$desc", loaded.id())
                assertEquals(
                    "status '$desc'",
                    oracle.id(save).status,
                    loaded.status(),
                )
            }
        }
    }

    @Test(timeout = 120_000)
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            for (seed in seeds) {
                val expected = oracle.newGame("untangle", params, seed)
                val actual = Midend.newGame(Untangle, params, seed)
                val label = "params '$params' seed '$seed'"
                assertEquals("id $label", expected.id, actual.id())
                assertEquals("save $label", expected.state, actual.serialise().serialise())
                assertEquals("aux $label", expected.state.auxRecord(), actual.auxRecord())
                assertNotNull("aux present $label", actual.auxInfo)
                assertTrue(
                    "aux format $label",
                    requireNotNull(actual.auxInfo).matches(Regex("S(;P\\d+:\\d+,\\d+/\\d+)+")),
                )
            }
        }
    }

    @Test(timeout = 120_000)
    fun `the numeric seed sweep matches the oracle`() = OracleClient().use { oracle ->
        for (params in listOf("6", "10")) {
            for (seed in 0..60) {
                val expected = oracle.newGame("untangle", params, seed.toString())
                val actual = Midend.newGame(Untangle, params, seed.toString())
                assertEquals("id '$params' seed $seed", expected.id, actual.id())
                assertEquals(
                    "aux '$params' seed $seed",
                    expected.state.auxRecord(),
                    actual.auxRecord(),
                )
            }
        }
    }

    @Test(timeout = 120_000)
    fun `the frozen corpus description and aux text are reproduced`() = OracleClient().use { oracle ->
        val save = oracle.newGame("untangle", "6", "12345")
        val actual = Midend.newGame(Untangle, "6", "12345")
        assertEquals(
            "0-1,0-2,0-3,0-5,1-3,1-4,1-5,2-3,2-4,2-5,3-4",
            actual.desc,
        )
        assertEquals(save.state.auxRecord(), actual.auxRecord())
    }

    /**
     * `GoldenDescTest` pins the oracle side of the golden file. This
     * pins the engine against the file directly, including the `""`
     * default entry that no other test generates.
     */
    @Test(timeout = 120_000)
    fun `the golden file entries match the engine and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(File(testdata, "golden/untangle"), "desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("untangle", golden.string("game"))
            assertEquals("semantic", golden.string("level"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("the default and five presets", 6, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = Midend.newGame(Untangle, params, seed).id()
                assertEquals("golden id params='$params'", entry.string("id"), id)
                assertEquals(
                    "oracle id params='$params'",
                    oracle.newGame("untangle", params, seed).id,
                    id,
                )
            }
        }

    private fun save(n: Int, desc: String): String =
        SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Untangle.name),
                SaveRecord("PARAMS", n.toString()),
                SaveRecord("CPARAMS", n.toString()),
                SaveRecord("DESC", desc),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()

    private fun String.auxRecord(): String? = SgtpSave.parse(this).value("AUXINFO")

    private fun Midend.auxRecord(): String? = serialise().serialise().auxRecord()

    private companion object {
        const val SEED = "12345"
    }
}
