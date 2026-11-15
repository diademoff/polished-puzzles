package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Divvy
import name.boyle.chris.sgtpuzzles.engine.Dsf
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: palisade.
 *
 * The description is a function of the params, the seed, and the whole
 * generator chain: the winnow-order shuffle, divvy, the solver gate,
 * the winnow order, and the run encoder. A difference in any of them
 * changes the bytes.
 */
class PalisadeDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf("5x5n5", "8x6n6", "10x8n8", "15x12n10")

    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `the presets match the oracle`() = OracleClient().use { oracle ->
        val expected = listOf(
            Preset("5 x 5, regions of size 5", "5x5n5"),
            Preset("8 x 6, regions of size 6", "8x6n6"),
            Preset("10 x 8, regions of size 8", "10x8n8"),
            Preset("15 x 12, regions of size 10", "15x12n10"),
        )
        assertEquals(expected, oracle.presets("palisade"))
        assertEquals(
            expected,
            Palisade.presets().map { Preset(it.title, it.params) },
        )
    }

    @Test
    fun `every frozen golden entry matches the engine`() {
        val golden = Json.parse(
            File(testdata, "golden/palisade/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("palisade", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertTrue(entries.size >= 5)
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = Midend.newGame(Palisade, params, "12345").id()
            assertEquals("params='$params'", entry.string("id"), id)
        }
    }

    @Test
    fun `the four presets at every seed match the oracle`() = OracleClient().use { oracle ->
        for (params in presets) {
            for (seed in seeds) {
                val expected = oracle.newGame("palisade", params, seed)
                val midend = Midend.newGame(Palisade, params, seed)
                assertEquals("$params seed '$seed' id", expected.id, midend.id())
                assertEquals(
                    "$params seed '$seed' status",
                    expected.status,
                    midend.status(),
                )
                // The full save, including the aux solution.
                assertEquals(
                    "$params seed '$seed' save",
                    expected.state,
                    midend.serialise().serialise(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep matches the oracle`() = OracleClient().use { oracle ->
        for ((params, last) in listOf("5x5n5" to 20, "8x6n6" to 10)) {
            for (seed in 0..last) {
                val expected = oracle.newGame("palisade", params, seed.toString())
                val actual = Midend.newGame(Palisade, params, seed.toString())
                assertEquals("$params seed $seed", expected.id, actual.id())
            }
        }
    }

    @Test
    fun `decode quirks and validation match the oracle`() = OracleClient().use { oracle ->
        val cases = listOf(
            "8",
            "8x6",
            "8x6n",
            "8x6n6x",
            "+8x6n6",
            "8X6N6",
            "5x5n25",
            "5x5n2",
            "1x4n2",
            "2x2n2",
            "5x5n1",
            "5x5n6",
            "0x5n5",
            "5x5n-1",
            "5x5n5 ",
            "5x5n05",
            "005x5n5",
            "5x5n+5",
            "5x5n 5",
            "5x5n5.5",
            "5x5n5n5",
            "5x5x5n5",
            "5n5",
            "5x5x",
            "5x5n",
            "x5n5",
            "5x0n5",
            "5x5n0",
            "0x5n0",
            "5x0n0",
            "5x5n4",
            "5x5n3",
            "1x2n1",
            "2x1n1",
            "1x6n2",
            "46341x46341n5",
        )
        for (params in cases) {
            val expected = try {
                oracle.newGame("palisade", params, "1").id
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Palisade, params, "1").id()
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }
    }

    @Test
    fun `the winnow shuffle and divvy match the oracle partition`() = OracleClient().use { oracle ->
        // The generator shuffles the winnow order before divvy. Reproduce
        // that one shuffle, then compare the division itself: roots,
        // sizes, attempt count, and the encoded RNG state.
        for (params in presets) {
            val p = Palisade.decodeParams(params) as Palisade.Params
            val random = RandomState.fromSeed("12345")
            val order = IntArray(p.w * p.h) { it }
            shuffle(order, random)

            val expected = oracle.divvy(p.w, p.h, p.k, state = random.encodeState())
            val actual = randomStreamDivvy(p.w, p.h, p.k, random)

            assertEquals("$params attempts", expected.attempts, actual.attempts)
            assertEquals("$params roots", expected.roots, actual.roots)
            assertEquals("$params sizes", expected.sizes, actual.sizes)
            assertEquals("$params state", expected.state, random.encodeState())
        }
    }

    @Test
    fun `the huge area is accepted by validation but refused at generation`() {
        // Upstream validation accepts 46340x46340 and generation then
        // does not terminate. The oracle probe is not run: it was killed
        // by the OS. D-007 pins the Kotlin guard and its message.
        assertEquals(
            null,
            Palisade.validateParams(Palisade.Params(46340, 46340, 5), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Palisade.validateParams(Palisade.Params(46341, 46341, 5), true),
        )
        val error = try {
            Palisade.newDesc(Palisade.Params(46340, 46340, 5), RandomState.fromSeed("1"))
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        assertEquals("Width times height is too large for this build", error)
    }

    /** One faithful `misc.c shuffle`, so the test can reach the divvy call. */
    private fun shuffle(array: IntArray, random: RandomState) {
        var i = array.size
        while (i-- > 1) {
            val j = random.upto((i + 1).toLong()).toInt()
            if (j != i) {
                val swap = array[i]
                array[i] = array[j]
                array[j] = swap
            }
        }
    }

    private class DivvyResult(
        val roots: List<Int>,
        val sizes: List<Int>,
        val attempts: Int,
    )

    private fun randomStreamDivvy(w: Int, h: Int, k: Int, random: RandomState): DivvyResult {
        var attempts = 0
        var dsf: Dsf? = null
        while (dsf == null) {
            attempts++
            dsf = Divvy.attempt(w, h, k, random)
        }
        return DivvyResult(
            roots = List(dsf.elementCount) { dsf.canonify(it) },
            sizes = List(dsf.elementCount) { dsf.size(it) },
            attempts = attempts,
        )
    }

    @Test
    fun `a generated save reloads through the engine`() {
        for (params in presets) {
            val midend = Midend.newGame(Palisade, params, "12345")
            val saved = midend.serialise().serialise()
            val loaded = Midend.fromSave(Palisade, SgtpSave.parse(saved))
            assertEquals("$params id", midend.id(), loaded.id())
            assertEquals("$params save", saved, loaded.serialise().serialise())
            assertEquals("$params aux", midend.auxInfo, loaded.auxInfo)
        }
    }
}
