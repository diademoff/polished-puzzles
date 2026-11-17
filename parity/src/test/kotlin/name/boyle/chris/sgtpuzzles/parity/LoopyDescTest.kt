package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: loopy.
 *
 * `params + seed -> DESC` is a function of the whole generator chain:
 * the aperiodic grid description, `generate_loop` with a null bias, the
 * clue-removal shuffle, the four solver stages, the uniqueness and
 * "too easy" retries, and the run-length clue encoder. A difference in
 * any of them changes the bytes.
 *
 * The loopy grid-type index is its own list, not the `GridType` order,
 * so every preset here also pins that translation.
 */
class LoopyDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf(
        "7x7t0de",
        "7x7t0dn",
        "7x7t0dh",
        "7x7t1dh",
        "5x5t3dh",
        "7x7t4dh",
        "5x5t7dh",
        "6x6t11dh",
        "6x6t12dh",
        "7x7t2dh",
        "5x4t5dh",
        "5x4t14dh",
        "5x5t6dh",
        "3x3t8dh",
        "3x3t9dh",
        "3x3t10dh",
        "3x2t13dh",
        "3x3t15dh",
        "6x6t16dh",
        "6x6t17dh",
    )

    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `the presets match the oracle`() = OracleClient().use { oracle ->
        val expected = listOf(
            Preset("7x7 Squares - Easy", "7x7t0de"),
            Preset("7x7 Squares - Normal", "7x7t0dn"),
            Preset("7x7 Squares - Hard", "7x7t0dh"),
            Preset("7x7 Triangular - Hard", "7x7t1dh"),
            Preset("5x5 Snub-Square - Hard", "5x5t3dh"),
            Preset("7x7 Cairo - Hard", "7x7t4dh"),
            Preset("5x5 Kites - Hard", "5x5t7dh"),
            Preset("6x6 Penrose (kite/dart) - Hard", "6x6t11dh"),
            Preset("6x6 Penrose (rhombs) - Hard", "6x6t12dh"),
            Preset("7x7 Honeycomb - Hard", "7x7t2dh"),
            Preset("4x5 Great-Hexagonal - Hard", "5x4t5dh"),
            Preset("4x5 Kagome - Hard", "5x4t14dh"),
            Preset("5x5 Octagonal - Hard", "5x5t6dh"),
            Preset("3x3 Floret - Hard", "3x3t8dh"),
            Preset("3x3 Dodecagonal - Hard", "3x3t9dh"),
            Preset("3x3 Great-Dodecagonal - Hard", "3x3t10dh"),
            Preset("2x3 Great-Great-Dodecagonal - Hard", "3x2t13dh"),
            Preset("3x3 Compass-Dodecagonal - Hard", "3x3t15dh"),
            Preset("6x6 Hats - Hard", "6x6t16dh"),
            Preset("6x6 Spectres - Hard", "6x6t17dh"),
        )
        assertEquals(expected, oracle.presets("loopy"))
        assertEquals(expected, Loopy.presets().map { Preset(it.title, it.params) })
        assertEquals("7x7t0de", Loopy.encodeParams(Loopy.defaultParams(), true))
        assertEquals("7x7t0", Loopy.encodeParams(Loopy.defaultParams(), false))
    }

    @Test
    fun `every frozen golden entry matches the engine`() {
        val golden = Json.parse(
            File(testdata, "golden/loopy/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("loopy", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(21, entries.size)
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            val midend = Midend.newGame(Loopy, params, "12345")
            assertEquals("params='$params'", id, midend.id())
            val save = midend.serialise()
            assertEquals("params='$params' DESC", id.substringAfter(':'), save.value("DESC"))
            assertNull("params='$params' AUXINFO", save.value("AUXINFO"))
        }
        // The empty entry is the short-encoder path with the default
        // parameters.
        assertEquals(
            Midend.newGame(Loopy, "7x7t0de", "12345").serialise().serialise(),
            Midend.newGame(Loopy, "", "12345").serialise().serialise(),
        )
    }

    @Test(timeout = 600_000)
    fun `the twenty presets at every seed match the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            for (seed in seeds) {
                val expected = oracle.newGame("loopy", params, seed)
                val midend = Midend.newGame(Loopy, params, seed)
                assertEquals("$params seed '$seed' id", expected.id, midend.id())
                assertEquals("$params seed '$seed' status", expected.status, midend.status())
                assertEquals(
                    "$params seed '$seed' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertNull("$params seed '$seed' AUXINFO", midend.serialise().value("AUXINFO"))
            }
        }
    }

    @Test(timeout = 600_000)
    fun `a numeric seed sweep matches the oracle`() = OracleClient().use { oracle ->
        for ((params, last) in listOf("7x7t0de" to 40, "7x7t0dn" to 30, "3x3t9dh" to 20)) {
            for (seed in 0..last) {
                val expected = oracle.newGame("loopy", params, seed.toString())
                val actual = Midend.newGame(Loopy, params, seed.toString())
                assertEquals("$params seed $seed", expected.id, actual.id())
            }
        }
    }

    @Test
    fun `the decode quirks and validation match the oracle`() = OracleClient().use { oracle ->
        // The decode table of work/specs/loopy.md section 1.4. The short
        // id drops the difficulty, so the full save is compared too.
        val decodes = listOf(
            "7x7",
            "7x7t4",
            "7x7t4dh",
            "7x7t4dejunk",
            "+7x+7t4",
            " 7x7t4",
            "007x7t4",
            "7x7t04dh",
            "7x7dZ",
            "7x7t",
            "7x7t4d",
            "7X7",
        )
        for (params in decodes) {
            val expected = oracle.newGame("loopy", params, "12345")
            val actual = Midend.newGame(Loopy, params, "12345")
            assertEquals("'$params' id", expected.id, actual.id())
            assertEquals("'$params' save", expected.state, actual.serialise().serialise())
        }

        // The one-argument seam decodes over the default parameters:
        // there is no `t`, so the grid type is Square.
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("7x7"))
        assertEquals(Loopy.Params(0, 0, 0, 0), Loopy.decodeParams("zzz"))
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("+7x+7"))
        assertEquals(Loopy.Params(7, 7, 3, 4), Loopy.decodeParams("7x7t04dh"))
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("7x7dZ"))

        val invalid = listOf(
            "2x7t0de",
            "7x2t0de",
            "7x7t18de",
            "7x7t-1de",
            "7x-7",
            "46341x46341t0dh",
        )
        for (params in invalid) {
            val expected = try {
                oracle.newGame("loopy", params, "1").id
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Loopy, params, "1").id()
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params' error", expected, actual)
        }

        // The accepted huge boundary, and the per-type size messages in
        // C order. Square requires `(w + 1) * (h + 1) <= INT_MAX`, so
        // 46339 is the largest accepted side and 46340 is rejected; the
        // oracle `grid` `size` operation returns the same two answers.
        assertEquals(
            "46339x46339t0de",
            Loopy.encodeParams(Loopy.decodeParams("46339x46339t0de"), true),
        )
        assertNull(Loopy.validateParams(Loopy.Params(46339, 46339, 0, 0), true))
        assertEquals(
            "Grid size must not be unreasonably large",
            Loopy.validateParams(Loopy.Params(46340, 46340, 0, 0), true),
        )
        assertEquals(
            "Illegal grid type",
            Loopy.validateParams(Loopy.Params(7, 7, 0, 18), true),
        )
        assertEquals(
            "Width and height for this grid type must both be at least 6",
            Loopy.validateParams(Loopy.Params(5, 6, 0, 16), true),
        )
        assertEquals(
            "At least one of width and height for this grid type must be at least 2",
            Loopy.validateParams(Loopy.Params(1, 1, 0, 8), true),
        )
        assertEquals(
            "Grid size must not be unreasonably large",
            Loopy.validateParams(Loopy.Params(46341, 46341, 0, 0), true),
        )
        assertEquals(
            "Illegal grid type",
            Loopy.validateParams(Loopy.Params(7, 7, 0, 18), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Loopy.validateParams(Loopy.Params(7, 7, 4, 0), true),
        )
        // C asserts `diff < DIFF_MAX`; the port returns a message. The
        // encoder writes a bare `d` rather than reading past the table.
        assertEquals("7x7t0d", Loopy.encodeParams(Loopy.Params(7, 7, 4, 0), true))
    }

    @Test
    fun `the description grammar matches the oracle`() = OracleClient().use { oracle ->
        // A square board has no grid part, and a `_` is the grid error.
        val cases = listOf(
            "7x7t0de" to "a2b2c02c22a32b20a21a032c3c22a21b2a3a1a",
            "7x7t0de" to "0_a2b2c02c22a32b20a21a032c3c22a21b2a3a1a",
            "7x7t0de" to "!",
            "7x7t0de" to "a",
            "7x7t0de" to "zz",
            "7x7t0de" to "A",
            "7x7t1dh" to "d220a2d21a2i2a10a1l12a1c0a10d0a0b211c1c02a21b010b0a112d2b21c",
            "7x7t1dh" to "0_d220a2d21a2i2a10a1l12a1c0a10d0a0b211c1c02a21b010b0a112d2b21c",
            "6x6t11dh" to "b4a07a0a55c2a",
        )
        for ((params, desc) in cases) {
            val save = saveWithDesc(params, desc)
            val expected = runCatching { oracle.id(save) }
            val actual = runCatching {
                Midend.fromGameId(Loopy, "${shortParams(params)}:$desc")
            }
            assertEquals(
                "'$params' desc '${desc.take(20)}' accepts",
                expected.isSuccess,
                actual.isSuccess,
            )
            if (expected.isSuccess) {
                assertEquals(
                    "'$params' desc '${desc.take(20)}' id",
                    expected.getOrThrow().id,
                    actual.getOrThrow().id(),
                )
            }
        }

        // The raw C messages, pinned engine-side because the save
        // loader wraps every description error.
        assertEquals(
            "Unknown character in description",
            Loopy.validateDesc(Loopy.decodeParams("7x7t0de"), "!"),
        )
        assertEquals(
            "Description too short for board size",
            Loopy.validateDesc(Loopy.decodeParams("7x7t0de"), "a"),
        )
        assertEquals(
            "Description too long for board size",
            Loopy.validateDesc(Loopy.decodeParams("7x7t0de"), "zz"),
        )
        assertEquals(
            "Grid description strings not used with this grid type",
            Loopy.validateDesc(
                Loopy.decodeParams("7x7t0de"),
                "0_a2b2c02c22a32b20a21a032c3c22a21b2a3a1a",
            ),
        )

        // A generated description carries `_` only for the types that
        // have a grid description, and `0_` for triangular.
        val shapes = mapOf(
            "7x7t0de" to false,
            "7x7t1dh" to true,
            "7x7t2dh" to false,
            "5x4t5dh" to false,
            "6x6t11dh" to true,
            "6x6t12dh" to true,
            "6x6t16dh" to true,
            "6x6t17dh" to true,
            "5x5t3dh" to false,
            "7x7t4dh" to false,
        )
        for ((params, hasGridDesc) in shapes) {
            val midend = Midend.newGame(Loopy, params, "12345")
            assertEquals(
                "$params grid part",
                hasGridDesc,
                '_' in midend.desc,
            )
            if (params == "7x7t1dh") assertTrue(midend.desc.startsWith("0_"))
            assertNull(midend.auxInfo)
        }
    }

    @Test(timeout = 300_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // The engine test pins the retry counts. These saves carry the
        // final board, so they guard the same generator path through the
        // full id and save.
        val fixtures = listOf(
            "7x7t0de" to "28",
            "7x7t0dn" to "0",
            "7x7t0dh" to "33",
            "6x6t11dh" to "4",
            "6x6t16dh" to "38",
            "6x6t17dh" to "33",
        )
        for ((params, seed) in fixtures) {
            val expected = oracle.newGame("loopy", params, seed)
            val actual = Midend.newGame(Loopy, params, seed)
            assertEquals("$params/$seed id", expected.id, actual.id())
            assertEquals("$params/$seed save", expected.state, actual.serialise().serialise())
        }
    }

    private fun shortParams(full: String): String {
        val p = Loopy.decodeParams(full) as Loopy.Params
        return Loopy.encodeParams(p, false)
    }

    private fun saveWithDesc(params: String, desc: String): String =
        saveRecordText(params, desc)

    private fun saveRecordText(params: String, desc: String): String = buildString {
        append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
        append("VERSION :1:1\n")
        append("GAME    :5:Loopy\n")
        append("PARAMS  :${params.length}:$params\n")
        append("CPARAMS :${params.length}:$params\n")
        append("DESC    :${desc.length}:$desc\n")
        append("NSTATES :1:1\n")
        append("STATEPOS:1:1\n")
    }

    private companion object {
        const val SEED = "12345"
    }
}
