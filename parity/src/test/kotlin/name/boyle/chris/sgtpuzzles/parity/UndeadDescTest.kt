package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Undead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: undead.
 *
 * Parameters, presets, the decode quirks, the validation order, the
 * short/full difficulty split, `params + seed -> DESC` for every preset,
 * a list of named seeds, a numeric sweep, and the six retry gates.
 *
 * The generator is decided by the five-way grid draw, the stable
 * `num_monsters` sort, the `next_list` enumeration order inside
 * `get_unique`, one `random_upto(3)` per still-unfilled monster, the
 * `Float` ratio, and the solver's accept/reject result. The golden table,
 * the seed list, and the sweep are the guards against a reordering that
 * still yields valid puzzles.
 */
class UndeadDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun id(params: String, seed: String): String =
        Midend.newGame(Undead, params, seed).id()

    private fun message(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    @Test
    fun `presets match the oracle exactly`() = OracleClient().use { oracle ->
        val expected = oracle.presets("undead")
        val actual = Undead.presets()

        assertEquals(8, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals(
            listOf(
                "4x4 Easy", "4x4 Normal", "4x4 Tricky", "5x5 Easy",
                "5x5 Normal", "5x5 Tricky", "7x7 Easy", "7x7 Normal",
            ),
            expected.map { it.title },
        )
        assertEquals(
            listOf(
                "4x4de", "4x4dn", "4x4dt", "5x5de", "5x5dn", "5x5dt",
                "7x7de", "7x7dn",
            ),
            expected.map { it.params },
        )
    }

    @Test
    fun `the golden descriptions are pinned`() = OracleClient().use { oracle ->
        val golden = Json.parse(
            File(testdata, "golden/undead/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("undead", golden.string("game"))
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(9, entries.size)

        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val expected = entry.string("id")
            assertEquals("golden '$params'", expected, id(params, seed))
            assertEquals(
                "oracle '$params'",
                oracle.newGame("undead", params, seed).id,
                id(params, seed),
            )
            // The id uses the short params, so it never names the
            // difficulty.
            assertTrue("'$params' id has no difficulty", !expected.substringBefore(':').contains('d'))
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
        for (params in Undead.presets().map { it.params }) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("undead", params, seed).id,
                    id(params, seed),
                )
            }
        }
    }

    @Test
    fun `a numeric sweep pins the generator order`() = OracleClient().use { oracle ->
        for (params in Undead.presets().map { it.params }) {
            for (seed in 0..60) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("undead", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
        for (params in listOf("13x4", "3x18")) {
            for (seed in 0..10) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("undead", params, seed.toString()).id,
                    id(params, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "4x4",
            "4x4dn",
            "4x4de",
            "4x4dt",
            "4x4dte",
            "4x4d",
            "4x4dE",
            "4x4dnjunk",
            "4X5",
            " 4x4",
            "4x+4",
            "13x4",
            "18x3",
            "3x18",
        )) {
            assertEquals(
                "'$params'",
                oracle.newGame("undead", params, "12345").id,
                id(params, "12345"),
            )
        }

        for (params in listOf("4x", "0x0", "-4x4", "4x-4", "14x4", "19x3", "3x19")) {
            val expected = try {
                oracle.newGame("undead", params, "12345").id
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "'$params'",
                expected,
                message { Midend.newGame(Undead, params, "12345") },
            )
        }

        // The empty parameter string is the default 4x4 Normal.
        assertEquals(id("4x4dn", "12345"), id("", "12345"))
    }

    @Test
    fun `validation follows the C order and messages`() = OracleClient().use { oracle ->
        for (params in listOf(
            "2x4",
            "4x2",
            "0x0",
            "4x4dy",
            "4x4d3",
            "14x4",
            "18x3",
            "19x3",
            "3x19",
            "65536x3",
        )) {
            val expected = try {
                oracle.newGame("undead", params, "1").id
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "'$params'",
                expected,
                message { Midend.newGame(Undead, params, "1") },
            )
        }

        // A negative difficulty is not reachable from a validated input,
        // but the C validator has no lower bound.
        assertNull(Undead.validateParams(Undead.Params(4, 4, -1), true))
        // The area bound is the integer division 54 / h.
        assertEquals(18, 54 / 3)
        assertNull(Undead.validateParams(Undead.Params(18, 3, 0), true))
        assertNull(Undead.validateParams(Undead.Params(3, 18, 0), true))
        assertEquals(
            "Grid is too big",
            Undead.validateParams(Undead.Params(19, 3, 0), true),
        )
        assertEquals(
            "Grid is too big",
            Undead.validateParams(Undead.Params(3, 19, 0), true),
        )
        assertEquals(
            "Grid is too big",
            Undead.validateParams(Undead.Params(14, 4, 0), true),
        )
    }

    @Test
    fun `the short and full encodings split the save format`() = OracleClient().use { oracle ->
        for (preset in Undead.presets()) {
            val p = Undead.decodeParams(preset.params) as Undead.Params
            assertEquals(preset.params, Undead.encodeParams(p, true))
            assertEquals(preset.params.substringBefore('d'), Undead.encodeParams(p, false))

            val midend = Midend.newGame(Undead, preset.params, "12345")
            val save = midend.serialise()
            assertEquals(
                "PARAMS for '${preset.params}'",
                "4x4dn".let { if (p.h == 5) "5x5dn" else if (p.h == 7) "7x7dn" else it },
                save.value("PARAMS"),
            )
            assertEquals(
                "CPARAMS for '${preset.params}'",
                preset.params,
                save.value("CPARAMS"),
            )
            // The id uses the short form, so the full difficulty is
            // visible only in CPARAMS.
            assertEquals(preset.params.substringBefore('d'), midend.id().substringBefore(':'))
            assertNotEquals(preset.params, midend.id().substringBefore(':'))

            // C reads the same save.
            val loaded = oracle.id(save.serialise())
            assertEquals(preset.params, SgtpSave.parse(loaded.state).value("CPARAMS"))
            assertEquals(save.value("PARAMS"), SgtpSave.parse(loaded.state).value("PARAMS"))
        }
    }

    @Test
    fun `each retry gate is pinned by a seed`() = OracleClient().use { oracle ->
        // The reason in the comment is the first reject of the attempt
        // that the fixture reaches; the attempt count is the C loop count.
        val fixtures = listOf(
            // num_total <= 4
            Triple("4x4de", "41", 3),
            // the Float ratio outside 0.48 .. 0.78
            Triple("4x4de", "16", 2),
            // a path over max_length
            Triple("4x4de", "13", 2),
            Triple("7x7de", "2", 2),
            // fewer than three monster types
            Triple("4x4de", "950", 2),
            // a Tricky type with at most one member
            Triple("4x4dt", "2", 5),
            // the difficulty classifier
            Triple("4x4de", "111", 2),
            Triple("4x4dt", "3", 3),
        )
        for ((params, seed, attempts) in fixtures) {
            val generation = Undead.newDescWithAttempts(
                Undead.decodeParams(params),
                RandomState.fromSeed(seed),
            )
            assertEquals("'$params' seed '$seed' attempts", attempts, generation.attempts)
            assertTrue("'$params' seed '$seed' retried", generation.attempts > 1)
            assertEquals(
                "'$params' seed '$seed' id",
                oracle.newGame("undead", params, seed).id,
                id(params, seed),
            )
        }
    }

    @Test
    fun `a path longer than w*h is held and solves like the oracle`() =
        OracleClient().use { oracle ->
            // This 4x4 mirror board has a 22-cell path. The unpadded
            // `w*h` is 16, so a 16-entry buffer would lose the path; C's
            // real buffer is `(w+2)*(h+2) = 36`, and the port allocates
            // `4*36`. The path is below the padded size, so the oracle
            // result is defined and may be compared.
            val grid = "RaRLLLaRRaaLLRLR"
            val zero = "0,0,4,$grid" + ",0".repeat(16)
            val probe = Undead.newState(Undead.decodeParams("4x4dn"), zero) as Undead.State
            assertTrue(
                "max path is longer than w*h",
                probe.common.paths.maxOf { it.length } > 4 * 4,
            )

            // Derive consistent clues from the all-zombie assignment.
            val clues = IntArray(2 * (4 + 4))
            for (path in probe.common.paths) {
                var count = 0
                for (i in 0 until path.length) {
                    if (path.p[i] != -1) count++
                }
                clues[path.gridStart] = count
                clues[path.gridEnd] = count
            }
            val desc = "0,0,4," + grid + clues.joinToString("") { ",$it" }
            val save = buildString {
                fun record(key: String, value: String) {
                    append(key.padEnd(8)).append(':').append(value.length)
                        .append(':').append(value).append('\n')
                }
                record("SAVEFILE", "Simon Tatham's Portable Puzzle Collection")
                record("VERSION", "1")
                record("GAME", "Undead")
                record("PARAMS", "4x4dn")
                record("CPARAMS", "4x4dn")
                record("SEED", "12345")
                record("DESC", desc)
                record("NSTATES", "1")
                record("STATEPOS", "1")
            }

            val midend = Midend.fromSave(Undead, SgtpSave.parse(save))
            val state = midend.state as Undead.State
            assertEquals(22, state.common.paths.maxOf { it.length })
            assertEquals(oracle.id(save).id, midend.id())
            assertEquals(oracle.text(save), midend.textFormat())

            val expected = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            val attempt = Undead.solveAttempt(state, null)
            if (expected != null) {
                assertEquals("the same solve error", expected, attempt.error)
            } else {
                assertEquals(
                    "the same solve payload",
                    SgtpSave.parse(
                        oracle.solve(save).state,
                    ).value("SOLVE"),
                    attempt.move,
                )
            }
        }

    @Test
    fun `the generated description never sets AUXINFO`() = OracleClient().use { oracle ->
        for (params in Undead.presets().map { it.params }) {
            val generated = Undead.newDesc(
                Undead.decodeParams(params),
                RandomState.fromSeed("12345"),
            )
            assertNull("'$params' AUXINFO", generated.auxInfo)
            assertNull(
                "'$params' oracle AUXINFO",
                oracle.newGame("undead", params, "12345").state.let {
                    SgtpSave.parse(it).value("AUXINFO")
                },
            )
        }
    }
}
