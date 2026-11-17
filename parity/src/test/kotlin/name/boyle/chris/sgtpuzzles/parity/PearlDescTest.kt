package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: pearl.
 *
 * `params + seed -> DESC` is a function of the whole generator chain:
 * the square grid, the stateful loopgen bias, the solver gates, the
 * clue-removal order with its upstream bugs, and the run encoder. A
 * difference in any of them changes the bytes.
 */
class PearlDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf(
        "6x6de",
        "6x6dt",
        "8x8de",
        "8x8dt",
        "10x10de",
        "10x10dt",
        "12x8de",
        "12x8dt",
    )

    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `the presets match the oracle`() = OracleClient().use { oracle ->
        val expected = listOf(
            Preset("6x6 Easy", "6x6de"),
            Preset("6x6 Tricky", "6x6dt"),
            Preset("8x8 Easy", "8x8de"),
            Preset("8x8 Tricky", "8x8dt"),
            Preset("10x10 Easy", "10x10de"),
            Preset("10x10 Tricky", "10x10dt"),
            Preset("12x8 Easy", "12x8de"),
            Preset("12x8 Tricky", "12x8dt"),
        )
        assertEquals(expected, oracle.presets("pearl"))
        assertEquals(expected, Pearl.presets().map { Preset(it.title, it.params) })
        assertEquals("8x8dt", Pearl.encodeParams(Pearl.defaultParams(), true))
    }

    @Test
    fun `every frozen golden entry matches the engine`() {
        val golden = Json.parse(
            File(testdata, "golden/pearl/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("pearl", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(9, entries.size)
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            val midend = Midend.newGame(Pearl, params, "12345")
            assertEquals("params='$params'", id, midend.id())
            // The golden id carries the description, so compare the
            // save record too. `the eight presets at every seed match
            // the oracle` compares the whole save, including AUXINFO,
            // for all eight presets. The empty entry is the short-encoder
            // path with the same parameters and seed as `8x8de`, and the
            // equality below ties its full save to that oracle-compared
            // record.
            val save = midend.serialise()
            assertEquals("params='$params' DESC", id.substringAfter(':'), save.value("DESC"))
            assertTrue("params='$params' AUXINFO", save.value("AUXINFO") != null)
        }
        assertEquals(
            Midend.newGame(Pearl, "8x8de", "12345").serialise().serialise(),
            Midend.newGame(Pearl, "", "12345").serialise().serialise(),
        )
    }

    @Test(timeout = 300_000)
    fun `the eight presets at every seed match the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            for (seed in seeds) {
                val expected = oracle.newGame("pearl", params, seed)
                val midend = Midend.newGame(Pearl, params, seed)
                assertEquals("$params seed '$seed' id", expected.id, midend.id())
                assertEquals("$params seed '$seed' status", expected.status, midend.status())
                assertEquals(
                    "$params seed '$seed' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
            }
        }
    }

    @Test(timeout = 300_000)
    fun `a numeric seed sweep matches the oracle`() = OracleClient().use { oracle ->
        for ((params, last) in listOf("6x6de" to 99, "6x6dt" to 49)) {
            for (seed in 0..last) {
                val expected = oracle.newGame("pearl", params, seed.toString())
                val actual = Midend.newGame(Pearl, params, seed.toString())
                assertEquals("$params seed $seed", expected.id, actual.id())
            }
        }
    }

    @Test
    fun `the decode quirks and validation match the oracle`() = OracleClient().use { oracle ->
        // The decode table of work/specs/pearl.md section 1.4. The short
        // id drops the difficulty, so the full save is compared too.
        val decodes = listOf(
            "8x8",
            "8x8dt",
            "8x8d",
            "8x8dq",
            "8x8det",
            "8x8dtx",
            "8x8dtn",
            "8x8den",
            "8x8dn",
            "8x8n",
            "8x8nn",
            "8X8",
            "08x008",
            "+8x8",
            " 8x8",
            "8x+8",
            "8x8\tde",
        )
        for (params in decodes) {
            val expected = oracle.newGame("pearl", params, "12345")
            val actual = Midend.newGame(Pearl, params, "12345")
            assertEquals("'$params' id", expected.id, actual.id())
            assertEquals("'$params' save", expected.state, actual.serialise().serialise())
        }

        val invalid = listOf(
            "4x8",
            "8x4",
            "46341x46341",
            "5x5dt",
        )
        for (params in invalid) {
            val expected = try {
                oracle.newGame("pearl", params, "1").id
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Pearl, params, "1").id()
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params' error", expected, actual)
        }

        // 6x5 Tricky is accepted; 5x5 Tricky is not.
        val accepted = oracle.newGame("pearl", "6x5dt", "1")
        assertEquals(
            accepted.id,
            Midend.newGame(Pearl, "6x5dt", "1").id(),
        )
        assertEquals(
            "Width or height must be at least six for Tricky",
            runCatching { Midend.newGame(Pearl, "5x5dt", "1") }
                .exceptionOrNull()?.message,
        )
    }

    @Test(timeout = 300_000)
    fun `the retry fixtures match the oracle`() = OracleClient().use { oracle ->
        // These seeds reject at least one full clue array and repeat the
        // whole generation with the advanced RNG state. PearlTest pins
        // the attempt counts (3, 29, and 98).
        val fixtures = listOf(
            "6x6de" to "0",
            "6x6de" to "1",
            "6x6dt" to "6",
        )
        for ((params, seed) in fixtures) {
            val expected = oracle.newGame("pearl", params, seed)
            val midend = Midend.newGame(Pearl, params, seed)
            assertEquals("$params $seed id", expected.id, midend.id())
            assertEquals("$params $seed save", expected.state, midend.serialise().serialise())
        }
    }

    @Test
    fun `every auxiliary text is the loop and its length is the cell count`() {
        for (params in presets) {
            val midend = Midend.newGame(Pearl, params, "12345")
            val aux = midend.auxInfo ?: error("$params has no AUXINFO")
            val p = Pearl.decodeParams(params) as Pearl.Params
            assertEquals("$params aux length", p.w * p.h, aux.length)
            assertTrue("$params aux alphabet", aux.all { it in '0'..'9' || it in 'A'..'F' })
        }
    }

    @Test
    fun `the short encoder pins the empty parameter entry`() {
        // The empty string goes through the short encoder, which drops
        // the difficulty. The default preset is Tricky, so this is the
        // Easy game, not the default game.
        assertEquals(
            Midend.newGame(Pearl, "8x8de", "12345").id(),
            Midend.newGame(Pearl, "", "12345").id(),
        )
        // The empty request starts from the short encoding of the default
        // preset, so both parameter sets are Easy.
        val empty = Midend.newGame(Pearl, "", "12345")
        assertEquals(Pearl.Params(8, 8, 0, false), empty.currentParams)
        assertEquals(Pearl.Params(8, 8, 0, false), empty.params)
        assertEquals("8x8de", Pearl.encodeParams(empty.params, true))
    }
}
