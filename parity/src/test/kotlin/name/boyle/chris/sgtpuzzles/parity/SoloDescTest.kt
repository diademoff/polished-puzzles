package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Solo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: solo.
 *
 * `params + seed -> DESC` is a function of the whole generator chain:
 * the `divvy` jigsaw partition, the killer cages, the randomized
 * backtracking fill, the symmetry classes, the clue removal loop, the
 * deduction solver's difficulty grading, and the run encoders. The
 * solver also decides `AUXINFO`, so a difference in the deduction order
 * changes the stored answer as well as the description bytes.
 *
 * The vendored `merge_some_cages` writes each candidate pair but never
 * increments `npairs` (`solo.c:3487-3494`), so its list is always empty
 * and it always returns false. A generated killer puzzle therefore
 * keeps the initial random cages, and the port reproduces that.
 */
class SoloDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf(
        "2x2", "2x3db", "3x3", "3x3db", "3x3xdb", "3x3di", "3x3da",
        "3x3xda", "3x3de", "3x3du", "3x3ka", "9jdb", "9jxdb", "9jda",
        "3x4db", "4x4db",
    )

    private val seeds = listOf("1", "2", "3", "12345", "seed", "0", "-7", "")

    @Test(timeout = 60_000)
    fun `the presets match the oracle`() = OracleClient().use { oracle ->
        val expected = listOf(
            Preset("2x2 Trivial", "2x2"),
            Preset("2x3 Basic", "2x3db"),
            Preset("3x3 Trivial", "3x3"),
            Preset("3x3 Basic", "3x3db"),
            Preset("3x3 Basic X", "3x3xdb"),
            Preset("3x3 Intermediate", "3x3di"),
            Preset("3x3 Advanced", "3x3da"),
            Preset("3x3 Advanced X", "3x3xda"),
            Preset("3x3 Extreme", "3x3de"),
            Preset("3x3 Unreasonable", "3x3du"),
            Preset("3x3 Killer", "3x3ka"),
            Preset("9 Jigsaw Basic", "9jdb"),
            Preset("9 Jigsaw Basic X", "9jxdb"),
            Preset("9 Jigsaw Advanced", "9jda"),
            Preset("3x4 Basic", "3x4db"),
            Preset("4x4 Basic", "4x4db"),
        )
        assertEquals(expected, oracle.presets("solo"))
        assertEquals(expected, Solo.presets().map { Preset(it.title, it.params) })
        assertEquals("3x3", Solo.encodeParams(Solo.defaultParams(), true))
        assertEquals("3x3", Solo.encodeParams(Solo.defaultParams(), false))
    }

    @Test(timeout = 120_000)
    fun `every frozen golden entry matches the engine`() {
        val golden = Json.parse(
            File(testdata, "golden/solo/desc.json").readText(),
        ) as JsonValue.Obj
        assertEquals("solo", golden.string("game"))
        assertEquals("12345", golden.string("seed"))
        val entries = (golden.field("entries") as JsonValue.Arr).items
        assertEquals(17, entries.size)
        for (item in entries) {
            val entry = item as JsonValue.Obj
            val params = entry.string("params")
            val id = entry.string("id")
            val midend = Midend.newGame(Solo, params, "12345")
            assertEquals("params='$params'", id, midend.id())
            val save = midend.serialise()
            assertEquals("params='$params' DESC", id.substringAfter(':'), save.value("DESC"))
            assertNotNull("params='$params' AUXINFO", save.value("AUXINFO"))
            assertNull("params='$params' UI", save.value("UI"))
        }
    }

    @Test(timeout = 900_000)
    fun `every preset and seed matches the oracle save`() =
        OracleClient().use { oracle ->
            for (params in presets) {
                for (seed in seeds) {
                    val expected = try {
                        oracle.newGame("solo", params, seed)
                    } catch (e: OracleException) {
                        throw AssertionError("$params '$seed' failed in C: ${e.message}", e)
                    }
                    val midend = Midend.newGame(Solo, params, seed)
                    assertEquals("$params '$seed' id", expected.id, midend.id())
                    assertEquals(
                        "$params '$seed' save",
                        expected.state,
                        midend.serialise().serialise(),
                    )
                    assertEquals("$params '$seed' status", expected.status, midend.status())
                    assertNotNull("$params '$seed' AUXINFO", midend.auxInfo)
                }
            }
        }

    @Test(timeout = 300_000)
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val decodes = listOf(
            "",
            "3x3",
            "3x3db",
            "3x3x",
            "3x3k",
            "3x3ka",
            "3x3m8db",
            "3x3md4da",
            "3x3r4de",
            "3x3a",
            "3x3z",
            "3x3d",
            "3x3dq",
            "3x3duu",
            "3x3j",
            "3x1",
            "007x7",
            "+7x+7",
            "7X7",
            "3x3k9",
            "zzz",
            "3x3killer",
        )
        for (params in decodes) {
            val expected = runCatching { oracle.newGame("solo", params, "1") }
            val actual = runCatching { Midend.newGame(Solo, params, "1") }
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

        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.decodeParams("3x3"))
        assertEquals(Solo.Params(3, 3, 1, 1, false, false), Solo.decodeParams("3x3db"))
        assertEquals(Solo.Params(9, 1, 1, 1, false, false), Solo.decodeParams("3x3jdb"))
        assertEquals(Solo.Params(3, 3, 7, 0, false, false), Solo.decodeParams("3x3m8"))
        assertEquals(Solo.Params(3, 3, 3, 3, false, false), Solo.decodeParams("3x3m2da"))
        assertEquals(Solo.Params(3, 3, 1, 0, true, false), Solo.decodeParams("3x3x"))
        assertEquals(Solo.Params(3, 3, 1, 0, false, true), Solo.decodeParams("3x3k"))
        assertEquals(Solo.Params(3, 3, 0, 0, false, false), Solo.decodeParams("3x3a"))
        // A `d` consumes one character even when it names no difficulty.
        assertEquals(Solo.Params(3, 3, 1, 0, false, false), Solo.decodeParams("3x3dz"))
        assertEquals("3x3md4da", Solo.encodeParams(Solo.Params(3, 3, 6, 3, false, false), true))
        assertEquals("3x3", Solo.encodeParams(Solo.Params(3, 3, 1, 0, false, false), false))
        assertEquals("3x3k", Solo.encodeParams(Solo.Params(3, 3, 1, 0, false, true), false))
        assertEquals("9jx", Solo.encodeParams(Solo.Params(9, 1, 1, 1, true, false), false))
        // The encoder has no default arm, so an unknown difficulty or
        // symmetry writes nothing rather than a `d`.
        assertEquals("3x3", Solo.encodeParams(Solo.Params(3, 3, 9, 9, false, false), true))
        assertEquals("3x3", Solo.encodeParams(Solo.Params(3, 3, -1, -1, false, false), true))
    }

    @Test(timeout = 120_000)
    fun `the validation messages match the oracle in C order`() =
        OracleClient().use { oracle ->
            val invalid = listOf(
                "1x3",
                "0x0",
                "-1x3",
                "256x3",
                "3x256",
                "32j",
                "6x6k",
                "10x10k",
                "1x3x",
            )
            for (params in invalid) {
                val expected = try {
                    oracle.newGame("solo", params, "1").id
                } catch (e: OracleException) {
                    e.message
                }
                val actual = try {
                    Midend.newGame(Solo, params, "1").id()
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                assertEquals("'$params' error", expected, actual)
            }

            assertEquals(
                "Both dimensions must be at least 2",
                Solo.validateParams(Solo.Params(1, 3, 1, 0, false, false), true),
            )
            assertEquals(
                "Dimensions greater than 255 are not supported",
                Solo.validateParams(Solo.Params(256, 3, 1, 0, false, false), true),
            )
            assertEquals(
                "Unable to support more than 31 distinct symbols in a puzzle",
                Solo.validateParams(Solo.Params(32, 1, 1, 0, false, false), true),
            )
            assertEquals(
                "Killer puzzle dimensions must be smaller than 10",
                Solo.validateParams(Solo.Params(3, 4, 1, 0, false, true), true),
            )
            assertEquals(
                "X-type puzzle dimensions must be larger than 3",
                Solo.validateParams(Solo.Params(2, 1, 1, 0, true, false), true),
            )
            assertNull(Solo.validateParams(Solo.Params(3, 3, 1, 0, false, false), true))
            assertNull(Solo.validateParams(Solo.Params(31, 1, 1, 0, false, false), true))
            // C accepts `r == 0` and `r < 0`; only generation and the
            // state builder refuse them (D-024).
            assertNull(Solo.validateParams(Solo.Params(3, 0, 1, 0, false, false), true))
            assertNull(Solo.validateParams(Solo.Params(3, -1, 1, 0, false, false), true))
        }

    @Test(timeout = 300_000)
    fun `the description grammar matches the oracle`() = OracleClient().use { oracle ->
        val good3 = "1_4c3_9b2a6_7j6a5_2b3_2e6b3a7b8e9_7b5_8a1j2_8a9b7_6c3_5"
        val cases = listOf(
            "3x3" to good3,
            "3x3" to "",
            "3x3" to "i".repeat(9),
            "3x3" to "a".repeat(81),
            "3x3" to "1",
            "3x3" to "1".repeat(81),
            "3x3" to "10".repeat(81),
            "3x3" to "A",
            "3x3" to ".",
            "3x3" to "a".repeat(82),
            "3x3" to good3 + "x",
            "3x3" to good3 + ",x",
            "3x3" to "0".repeat(81),
            "3x3" to "_".repeat(81),
            "3x3x" to "i".repeat(9),
        )
        for ((params, desc) in cases) {
            val save = saveWithDesc(params, params, desc)
            val expected = runCatching { oracle.id(save) }
            val actual = runCatching { Midend.fromGameId(Solo, "$params:$desc") }
            assertEquals("'$params:$desc' accepts", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                assertEquals(
                    "'$params:$desc' id",
                    expected.getOrThrow().id,
                    actual.getOrThrow().id(),
                )
                assertEquals(
                    "'$params:$desc' save",
                    expected.getOrThrow().state,
                    actual.getOrThrow().serialise().serialise(),
                )
            }
        }

        assertEquals(
            "Invalid character in game description",
            Solo.validateDesc(Solo.decodeParams("3x3"), "!"),
        )
        assertEquals(
            "Number out of range in game description",
            Solo.validateDesc(Solo.decodeParams("3x3"), "10" + "a".repeat(80)),
        )
        assertEquals(
            "Not enough data to fill grid",
            Solo.validateDesc(Solo.decodeParams("3x3"), "a"),
        )
        assertEquals(
            "Too much data to fit in grid",
            Solo.validateDesc(Solo.decodeParams("3x3"), "a".repeat(82)),
        )
        // A dot is a text-format character, not a description character.
        assertEquals(
            "Invalid character in game description",
            Solo.validateDesc(Solo.decodeParams("3x3"), "."),
        )
        // `_` is a no-op separator, and a digit is a clue.
        assertNull(Solo.validateDesc(Solo.decodeParams("3x3"), "_1" + "a".repeat(80)))
    }

    @Test(timeout = 300_000)
    fun `the jigsaw and killer suffix grammar matches the oracle`() =
        OracleClient().use { oracle ->
            val jigsaw = oracle.newGame("solo", "9jdb", "12345").id.substringAfter(':')
            val killer = oracle.newGame("solo", "3x3ka", "12345").id.substringAfter(':')
            val grid9 = jigsaw.substringBefore(',')
            val blocks9 = jigsaw.substringAfter(',').substringBefore(',')
            val kgrid = killer.substringAfterLast(',')
            val kblocks = killer.substringAfter(',').substringBeforeLast(',')
            val kgrid3 = killer.substringBefore(',')

            val cases = listOf(
                "9jdb" to grid9,
                "9jdb" to "$grid9,",
                "9jdb" to "$grid9,$blocks9,",
                "9jdb" to "$grid9,!",
                "9jdb" to "$grid9,${blocks9}z",
                "9jdb" to "$grid9,${blocks9}zz",
                "9jdb" to "$grid9,$blocks9,x",
                "9jdb" to "$grid9,$blocks9,${blocks9}",
                "3x3k" to kgrid3,
                "3x3k" to "$kgrid3,",
                "3x3k" to "$kgrid3,$kblocks",
                "3x3k" to "$kgrid3,$kblocks,",
                "3x3k" to "$kgrid3,$kblocks,$kgrid,x",
                "3x3k" to "$kgrid3,$kblocks,${kgrid}10",
                "3x3k" to "$kgrid3,$kblocks,${kgrid}a",
                "3x3k" to "a".repeat(81) + ",$kblocks,$kgrid",
            )
            for ((params, desc) in cases) {
                val save = saveWithDesc(params, params, desc)
                val expected = runCatching { oracle.id(save) }
                val actual = runCatching { Midend.fromGameId(Solo, "$params:$desc") }
                assertEquals("'$params' '${desc.take(20)}...' accepts", expected.isSuccess, actual.isSuccess)
                if (expected.isSuccess) {
                    assertEquals(
                        "'$params' id",
                        expected.getOrThrow().id,
                        actual.getOrThrow().id(),
                    )
                }
            }

            assertEquals(
                "Expected jigsaw block structure in game description",
                Solo.validateDesc(Solo.decodeParams("9jdb"), grid9),
            )
            assertEquals(
                "Expected killer block structure in game description",
                Solo.validateDesc(Solo.decodeParams("3x3k"), kgrid3),
            )
            assertEquals(
                "Expected killer clue grid in game description",
                Solo.validateDesc(Solo.decodeParams("3x3k"), "$kgrid3,$kblocks"),
            )
        }

    @Test(timeout = 300_000)
    fun `the symmetries match the oracle`() = OracleClient().use { oracle ->
        for (params in listOf(
            "3x3m8db", "3x3m4db", "3x3md4db", "3x3m2db", "3x3md2db",
            "3x3r4db", "3x3adb", "2x3m8db", "4x4m8db",
        )) {
            val expected = oracle.newGame("solo", params, "12345")
            val midend = Midend.newGame(Solo, params, "12345")
            assertEquals("$params id", expected.id, midend.id())
            assertEquals("$params save", expected.state, midend.serialise().serialise())
        }
    }

    private fun saveWithDesc(params: String, cparams: String, desc: String): String = buildString {
        append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
        append("VERSION :1:1\n")
        append("GAME    :4:Solo\n")
        append("PARAMS  :${params.length}:$params\n")
        append("CPARAMS :${cparams.length}:$cparams\n")
        append("DESC    :${desc.length}:$desc\n")
        append("NSTATES :1:1\n")
        append("STATEPOS:1:1\n")
    }
}
