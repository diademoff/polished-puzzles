package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: rect.
 *
 * `params + seed -> DESC` is a function of the whole generator chain:
 * the random tiling, the singleton repairs, the two expansion passes
 * with their transpositions, the solver's randomized winnowing, and the
 * run encoder. The solver also decides `AUXINFO`, so a difference in
 * the deduction order changes the stored answer as well as the bytes.
 */
class RectDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf("7x7", "9x9", "11x11", "13x13", "15x15")

    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `the presets match the oracle`() = OracleClient().use { oracle ->
        val expected = listOf(
            Preset("7x7", "7x7"),
            Preset("9x9", "9x9"),
            Preset("11x11", "11x11"),
            Preset("13x13", "13x13"),
            Preset("15x15", "15x15"),
        )
        assertEquals(expected, oracle.presets("rect"))
        assertEquals(expected, Rect.presets().map { Preset(it.title, it.params) })
        assertEquals("7x7", Rect.encodeParams(Rect.defaultParams(), true))
    }

    @Test
    fun `every frozen golden entry matches the engine`() {
        val golden = Json.parse(
            File(testdata, "golden/rect/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("rect", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(6, entries.size)
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            val midend = Midend.newGame(Rect, params, "12345")
            assertEquals("params='$params'", id, midend.id())
            val save = midend.serialise()
            assertEquals("params='$params' DESC", id.substringAfter(':'), save.value("DESC"))
            assertNotNull("params='$params' AUXINFO", save.value("AUXINFO"))
        }
    }

    @Test
    fun `every preset and seed matches the oracle save`() =
        OracleClient().use { oracle ->
            for (params in presets) {
                for (seed in seeds) {
                    val expected = try {
                        oracle.newGame("rect", params, seed)
                    } catch (e: OracleException) {
                        throw AssertionError("$params '$seed' failed in C: ${e.message}", e)
                    }
                    val midend = Midend.newGame(Rect, params, seed)
                    assertEquals("$params '$seed' id", expected.id, midend.id())
                    assertEquals("$params '$seed' save", expected.state, midend.serialise().serialise())
                    assertEquals("$params '$seed' status", expected.status, midend.status())
                }
            }
        }

    @Test
    fun `expansion and non-unique modes match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "7x7a" to "1",
            "7x7e0.5" to "12345",
            "9x9e1" to "12345",
            "7x7e2" to "7",
            "8x5" to "12345",
            "5x8" to "12345",
            "7x7e0.25" to "3",
            "1x7" to "12345",
            "7x1" to "12345",
            "2x1" to "1",
        )
        for ((params, seed) in cases) {
            val expected = oracle.newGame("rect", params, seed)
            val midend = Midend.newGame(Rect, params, seed)
            assertEquals("$params id", expected.id, midend.id())
            assertEquals("$params save", expected.state, midend.serialise().serialise())
            assertNotNull("$params AUXINFO", midend.auxInfo)
            assertNull("$params PRIVDESC", midend.serialise().value("PRIVDESC"))
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val decodes = listOf(
            "7x7",
            "7x7a",
            "7x7e0.5",
            "7x7e1",
            "7x7e-1",
            "7x7e1a",
            "7x7e.5a",
            "007x7",
            "+7x+7",
            "7X7",
            "7x7z",
            "7x",
            "x7",
            "",
            "zzz",
        )
        for (params in decodes) {
            val expected = runCatching { oracle.newGame("rect", params, "12345") }
            val actual = runCatching { Midend.newGame(Rect, params, "12345") }
            assertEquals("'$params' accepts", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                assertEquals("'$params' id", expected.getOrThrow().id, actual.getOrThrow().id())
                assertEquals(
                    "'$params' save",
                    expected.getOrThrow().state,
                    actual.getOrThrow().serialise().serialise(),
                )
            } else {
                assertEquals(
                    "'$params' error",
                    expected.exceptionOrNull()!!.message,
                    actual.exceptionOrNull()!!.message,
                )
            }
        }

        assertEquals(Rect.Params(7, 7, 0.0f, true), Rect.decodeParams("7x7"))
        assertEquals(Rect.Params(7, 7, 0.5f, true), Rect.decodeParams("7x7e0.5"))
        assertEquals(Rect.Params(7, 7, 0.0f, false), Rect.decodeParams("7x7a"))
        assertEquals(Rect.Params(7, 7, -1.0f, true), Rect.decodeParams("7x7e-1"))
        assertEquals(Rect.Params(7, 7, 1.0f, false), Rect.decodeParams("7x7e1a"))
        assertEquals("7x7e0.5", Rect.encodeParams(Rect.Params(7, 7, 0.5f, true), true))
        assertEquals("7x7", Rect.encodeParams(Rect.Params(7, 7, 0.5f, true), false))
        assertEquals("7x7a", Rect.encodeParams(Rect.Params(7, 7, 0.0f, false), true))
    }

    @Test
    fun `the validation messages match the oracle in C order`() =
        OracleClient().use { oracle ->
            val invalid = listOf(
                "0x7",
                "7x0",
                "-1x7",
                "7x-1",
                "1x1",
                "7x7e-1",
                "46341x46341",
            )
            for (params in invalid) {
                val expected = try {
                    oracle.newGame("rect", params, "1").id
                } catch (e: OracleException) {
                    e.message
                }
                val actual = try {
                    Midend.newGame(Rect, params, "1").id()
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                assertEquals("'$params' error", expected, actual)
            }

            assertEquals(
                "Width and height must both be greater than zero",
                Rect.validateParams(Rect.Params(0, 7, 0.0f, true), true),
            )
            assertEquals(
                "Width times height must not be unreasonably large",
                Rect.validateParams(Rect.Params(46341, 46341, 0.0f, true), true),
            )
            assertEquals(
                "Grid area must be greater than one",
                Rect.validateParams(Rect.Params(1, 1, 0.0f, true), true),
            )
            assertEquals(
                "Expansion factor may not be negative",
                Rect.validateParams(Rect.Params(7, 7, -0.1f, true), true),
            )
            assertNull(Rect.validateParams(Rect.Params(7, 7, 0.0f, true), true))
            assertNull(Rect.validateParams(Rect.Params(2, 1, 0.0f, true), true))
    }

    @Test
    fun `the description grammar matches the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "7x7" to "3_2a2_3g2f5_2a6a3_3b2_2d4b3_2c5f",
            "7x7" to "!",
            "7x7" to "",
            "7x7" to "a",
            "7x7" to "z",
            "7x7" to "3",
            "7x7" to "49",
            "7x7" to "2a2",
            "7x7" to "0",
        )
        for ((params, desc) in cases) {
            val save = saveWithDesc(params, desc)
            val expected = runCatching { oracle.id(save) }
            val actual = runCatching { Midend.fromGameId(Rect, "$params:$desc") }
            assertEquals("'$desc' accepts", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                assertEquals("'$desc' id", expected.getOrThrow().id, actual.getOrThrow().id())
            }
        }

        assertEquals(
            "Invalid character in game description",
            Rect.validateDesc(Rect.decodeParams("7x7"), "!"),
        )
        assertEquals(
            "Not enough data to fill grid",
            Rect.validateDesc(Rect.decodeParams("7x7"), "a"),
        )
        assertEquals(
            "Not enough data to fill grid",
            Rect.validateDesc(Rect.decodeParams("7x7"), "z"),
        )
        assertEquals(
            "Too much data to fit in grid",
            Rect.validateDesc(Rect.decodeParams("7x7"), "zz"),
        )
    }

    @Test
    fun `the generator is deterministic for a seed`() {
        val p = Rect.decodeParams("7x7")
        val first = Rect.newDesc(p, RandomState.fromSeed("12345"))
        val second = Rect.newDesc(p, RandomState.fromSeed("12345"))
        assertEquals(first, second)
        val other = Rect.newDesc(p, RandomState.fromSeed("12346"))
        assertTrue(first.text != other.text)
        assertNotNull(first.auxInfo)
    }

    private fun saveWithDesc(params: String, desc: String): String = buildString {
        append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
        append("VERSION :1:1\n")
        append("GAME    :10:Rectangles\n")
        append("PARAMS  :${params.length}:$params\n")
        append("CPARAMS :${params.length}:$params\n")
        append("DESC    :${desc.length}:$desc\n")
        append("NSTATES :1:1\n")
        append("STATEPOS:1:1\n")
    }
}
