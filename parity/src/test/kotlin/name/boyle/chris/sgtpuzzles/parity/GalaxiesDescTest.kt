package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: galaxies.
 *
 * `params + seed -> DESC` is a function of the whole generator chain:
 * the shuffled space list, the block expansion and dot moves, the
 * wiggliness choice over ten attempts, the outline pass, the solver
 * gate at the requested difficulty, and the run encoder. The solver
 * also decides `AUXINFO`, so a difference in the deduction order
 * changes the stored answer as well as the description bytes.
 */
class GalaxiesDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf("7x7dn", "7x7du", "10x10dn", "10x10du", "15x15dn", "15x15du")

    private val seeds = listOf("1", "2", "3", "12345", "seed", "0", "-7", "")

    @Test
    fun `the presets match the oracle`() = OracleClient().use { oracle ->
        val expected = listOf(
            Preset("7x7 Normal", "7x7dn"),
            Preset("7x7 Unreasonable", "7x7du"),
            Preset("10x10 Normal", "10x10dn"),
            Preset("10x10 Unreasonable", "10x10du"),
            Preset("15x15 Normal", "15x15dn"),
            Preset("15x15 Unreasonable", "15x15du"),
        )
        assertEquals(expected, oracle.presets("galaxies"))
        assertEquals(expected, Galaxies.presets().map { Preset(it.title, it.params) })
        assertEquals("7x7dn", Galaxies.encodeParams(Galaxies.defaultParams(), true))
        assertEquals("7x7", Galaxies.encodeParams(Galaxies.defaultParams(), false))
    }

    @Test
    fun `every frozen golden entry matches the engine`() {
        val golden = Json.parse(
            File(testdata, "golden/galaxies/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("galaxies", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(7, entries.size)
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            val midend = Midend.newGame(Galaxies, params, "12345")
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
                        oracle.newGame("galaxies", params, seed)
                    } catch (e: OracleException) {
                        throw AssertionError("$params '$seed' failed in C: ${e.message}", e)
                    }
                    val midend = Midend.newGame(Galaxies, params, seed)
                    assertEquals("$params '$seed' id", expected.id, midend.id())
                    assertEquals("$params '$seed' save", expected.state, midend.serialise().serialise())
                    assertEquals("$params '$seed' status", expected.status, midend.status())
                }
            }
        }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val decodes = listOf(
            "7x7",
            "7x7dn",
            "7x7du",
            "7x7d",
            "7x7dx",
            "7x7dun",
            "7x7x",
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
            val expected = runCatching { oracle.newGame("galaxies", params, "12345") }
            val actual = runCatching { Midend.newGame(Galaxies, params, "12345") }
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

        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7"))
        assertEquals(Galaxies.Params(7, 7, 1), Galaxies.decodeParams("7x7du"))
        // A `d` always consumes the next character, whether or not it
        // names a difficulty.
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7dx"))
        assertEquals(Galaxies.Params(7, 7, 0), Galaxies.decodeParams("7x7d"))
        assertEquals("7x7dn", Galaxies.encodeParams(Galaxies.Params(7, 7, 0), true))
        assertEquals("7x7du", Galaxies.encodeParams(Galaxies.Params(7, 7, 1), true))
        assertEquals("7x7", Galaxies.encodeParams(Galaxies.Params(7, 7, 1), false))
    }

    @Test
    fun `the validation messages match the oracle in C order`() =
        OracleClient().use { oracle ->
            val invalid = listOf(
                "2x3",
                "3x2",
                "0x0",
                "-1x7",
                "7x-1",
                "2x2dn",
            )
            for (params in invalid) {
                val expected = try {
                    oracle.newGame("galaxies", params, "1").id
                } catch (e: OracleException) {
                    e.message
                }
                val actual = try {
                    Midend.newGame(Galaxies, params, "1").id()
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                assertEquals("'$params' error", expected, actual)
            }

            assertEquals(
                "Width and height must both be at least 3",
                Galaxies.validateParams(Galaxies.Params(2, 3, 0), true),
            )
            assertNull(Galaxies.validateParams(Galaxies.Params(3, 3, 0), true))
            assertNull(Galaxies.validateParams(Galaxies.Params(3, 3, 1), true))
            assertEquals(
                "Width times height must not be unreasonably large",
                Galaxies.validateParams(Galaxies.Params(46341, 46341, 0), true),
            )
            // The C assert on a hand-made difficulty is a message in
            // the port (D-023).
            assertEquals(
                "Unknown difficulty level",
                Galaxies.validateParams(Galaxies.Params(7, 7, -1), true),
            )
            assertEquals(
                "Unknown difficulty level",
                Galaxies.validateParams(Galaxies.Params(7, 7, 2), true),
            )
            // The C array ends with a NUL, so a difficulty of 2 writes
            // a bare `d`; an out-of-range difficulty is defined the
            // same way instead of reading the array out of bounds.
            assertEquals("7x7d", Galaxies.encodeParams(Galaxies.Params(7, 7, 2), true))
        }

    @Test
    fun `the description grammar matches the oracle`() = OracleClient().use { oracle ->
        val good = "cjrbhizuisfvf"
        val cases = listOf(
            "7x7dn" to good,
            "7x7dn" to "",
            "7x7dn" to "!",
            "7x7dn" to "a",
            "7x7dn" to "z",
            "7x7dn" to "zz",
            "7x7dn" to "yyyyyyy",
            "7x7dn" to "Aaaaaaa",
            "3x3dn" to "aaaaaaaa",
        )
        for ((params, desc) in cases) {
            val save = saveWithDesc(params, desc)
            val expected = runCatching { oracle.id(save) }
            val actual = runCatching { Midend.fromGameId(Galaxies, "$params:$desc") }
            assertEquals("'$desc' accepts", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                assertEquals("'$desc' id", expected.getOrThrow().id, actual.getOrThrow().id())
            }
        }

        assertEquals(
            "Invalid character in game description",
            Galaxies.validateDesc(Galaxies.decodeParams("7x7dn"), "!"),
        )
        // The first `a` lands on the top-left cell, and the next cell is
        // too close to its right, so the pair is rejected by the solver.
        assertEquals(
            "Dots too close together",
            Galaxies.validateDesc(Galaxies.decodeParams("7x7dn"), "aa"),
        )
    }

    @Test
    fun `two seeds give different descriptions`() {
        val p = Galaxies.decodeParams("7x7dn")
        val first = Galaxies.newDesc(p, RandomState.fromSeed("12345"))
        val second = Galaxies.newDesc(p, RandomState.fromSeed("12345"))
        assertEquals(first, second)
        val other = Galaxies.newDesc(p, RandomState.fromSeed("12346"))
        assertNotEquals(first.text, other.text)
        assertNotNull(first.auxInfo)
        assertTrue(first.auxInfo!!.startsWith("S;"))
    }

    private fun saveWithDesc(params: String, desc: String): String = buildString {
        append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
        append("VERSION :1:1\n")
        append("GAME    :8:Galaxies\n")
        append("PARAMS  :${params.length}:$params\n")
        append("CPARAMS :${params.length}:$params\n")
        append("DESC    :${desc.length}:$desc\n")
        append("NSTATES :1:1\n")
        append("STATEPOS:1:1\n")
    }
}
