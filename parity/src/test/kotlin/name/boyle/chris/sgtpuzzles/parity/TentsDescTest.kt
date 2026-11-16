package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.Tents
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: tents.
 *
 * Parameters, presets, the decode quirks, the validation order, and
 * `params + seed -> DESC` for every preset, a list of named seeds, and
 * a numeric sweep. The `treemap` id order, the adjacency-list order,
 * the seeded matching, and the retry condition all feed the stream, so
 * the sweep is the guard against a reordering that still yields valid
 * puzzles.
 */
class TentsDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Tents, params, seed).id()

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("tents")
        val actual = Tents.presets()

        assertEquals(6, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(
            listOf("8x8 Easy", "8x8 Tricky", "10x10 Easy", "10x10 Tricky",
                "15x15 Easy", "15x15 Tricky"),
            expected.map { it.title },
        )
        assertEquals(
            listOf("8x8de", "8x8dt", "10x10de", "10x10dt", "15x15de", "15x15dt"),
            expected.map { it.params },
        )
    }

    @Test
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/tents/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("tents", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(7, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, seed))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("tents", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val seeds = listOf(
            "1",
            "2",
            "3",
            "12345",
            "seed",
            "another seed",
            "0",
            "-7",
            "",
        )
        for (params in Tents.presets().map { it.params }) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("tents", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        for (params in listOf("8x8de", "8x8dt", "4x4de", "4x4dt", "5x4dt")) {
            for (seed in 0..60) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("tents", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the difficulty downgrade only applies to 4x4`() = OracleClient().use { oracle ->
        for (seed in listOf("12345", "1", "another seed")) {
            assertEquals(
                "4x4 seed $seed",
                oracle.newGame("tents", "4x4de", seed).id,
                oracle.newGame("tents", "4x4dt", seed).id,
            )
            assertEquals(
                "4x4 seed $seed in Kotlin",
                id("4x4de", seed),
                id("4x4dt", seed),
            )
        }
        // 5x4 is not downgraded: `w <= 4 && h <= 4` fails.
        var differ = false
        for (seed in 0..10) {
            if (oracle.newGame("tents", "5x4de", seed.toString()).id !=
                oracle.newGame("tents", "5x4dt", seed.toString()).id
            ) {
                differ = true
            }
        }
        assertEquals(true, differ)
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "8",
            "8x8",
            "8x8d",
            "8x8dz",
            "8x8x8",
            "+8",
            "8X8",
            "08x09",
            "8x8 ",
            "8x8d0",
            "8x8q",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("tents", params, "12345").id,
                id(params, "12345"),
            )
        }

        // `8x` and `0x8` are rejected with the same message.
        for (params in listOf("8x", "0x8")) {
            val expected = try {
                oracle.newGame("tents", params, "12345").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Tents, params, "12345")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }
    }

    @Test
    fun `validation messages match the oracle in order`() = OracleClient().use { oracle ->
        for (params in listOf("3x8", "8x3", "0x8", "8x", "65536x65536")) {
            val expected = try {
                oracle.newGame("tents", params, "1").id
                null
            } catch (e: OracleException) {
                e.message
            }
            val actual = try {
                Midend.newGame(Tents, params, "1")
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
            assertEquals("'$params'", expected, actual)
        }

        // The accepted boundary is not generated: the area check only.
        assertEquals(null, Tents.validateParams(Tents.Params(46340, 46340, 0), true))
        assertEquals(null, Tents.validateParams(Tents.Params(46340, 46340, 1), false))
        assertEquals(
            "Width times height must not be unreasonably large",
            Tents.validateParams(Tents.Params(65536, 65536, 0), true),
        )
    }

    @Test
    fun `validateDesc uses the exact C messages`() {
        val p = Tents.Params(8, 8, 0)
        fun check(desc: String) = Tents.validateDesc(p, desc)

        assertEquals(
            "Tent or non-tent placed off the grid",
            check("!,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"),
        )
        assertEquals(
            "Invalid character in grid specification",
            check("?,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"),
        )
        assertEquals("Not enough data to fill grid", check("_"))
        assertEquals("Too much data to fit in grid", check("_".repeat(66)))
        assertEquals(
            "Not enough numbers given after grid specification",
            check("_".repeat(65)),
        )
        assertEquals(
            "Invalid character in number list",
            check("_".repeat(65) + ",0,0,0,0,0,0,0,0,0,0,0,0,0,0,x"),
        )
        assertEquals(
            "Unexpected additional data at end of game description",
            check("_".repeat(65) + ",0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,x"),
        )

        // An empty number is accepted. A negative number is not: the
        // validator only skips digits, so the sign is not a comma. A
        // huge number is accepted because its digits are simply skipped.
        assertEquals(null, check("_".repeat(65) + ",".repeat(16)))
        assertEquals(
            "Invalid character in number list",
            check("_".repeat(65) + ",-1".repeat(16)),
        )
        assertEquals(null, check("_".repeat(65) + ",99999999999" + ",0".repeat(15)))
    }

    @Test
    fun `the two C aborts are a defined port result`() {
        // Both pass `validateDesc` and abort the shipped C build:
        //   tents.c:1292: Assertion 'i >= 0 && i <= w*h' failed
        //   tents.c:1294: Assertion 'type == TREE' failed
        // The port throws instead; D-009 records this.
        val p = Tents.Params(8, 8, 0)
        val zeros = ",0".repeat(16)
        val overrun = "ddddddddz$zeros"
        val nonTree = "dddddddcz_$zeros"
        assertEquals(null, Tents.validateDesc(p, overrun))
        assertEquals(null, Tents.validateDesc(p, nonTree))

        val first = try {
            Tents.newState(p, overrun)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        assertEquals("tents: the description runs past the grid", first)
        val second = try {
            Tents.newState(p, nonTree)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        assertEquals("tents: the description ends on a non-tree cell", second)
    }

    @Test
    fun `the generated descriptions carry no AUXINFO`() {
        // The port generator answers from the initial grid, so it writes
        // no AUXINFO; the corpus saves still load and keep theirs.
        for (params in Tents.presets()) {
            val midend = Midend.newGame(Tents, params.params, "12345")
            val save = midend.serialise()
            assertEquals(params.params, save.value("CPARAMS"))
            assertEquals(Tents.encodeParams(params.let { Tents.decodeParams(it.params) }, false),
                midend.id().substringBefore(':'))
            assertEquals(null, save.value("AUXINFO"))
        }
    }
}
