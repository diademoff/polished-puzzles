package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.RandomState
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: mines.
 *
 * Parameters, presets, the decode and validation quirks, the
 * `PARAMS`/`CPARAMS` split, `params + seed -> DESC`, the bitmap
 * grammar, and the first-click layout.
 *
 * The layout tests are the RNG-order guards: a wrong draw order in the
 * solver or the perturbation changes the bitmap and therefore `DESC`,
 * `PRIVDESC`, and the saved move.
 */
class MinesDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the presets and the default match the oracle`() = OracleClient().use { oracle ->
        val expected = oracle.presets("mines")
        val actual = Mines.presets()
        assertEquals(4, expected.size)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("preset $i title", expected[i].title, actual[i].title)
            assertEquals("preset $i params", expected[i].params, actual[i].params)
        }
        assertEquals("9x9, 10 mines", actual[0].title)
        assertEquals("9x9n10", actual[0].params)
        assertEquals("9x9, 35 mines", actual[1].title)
        assertEquals("16x16, 40 mines", actual[2].title)
        assertEquals("16x16, 99 mines", actual[3].title)

        val defaults = oracle.newGame("mines", "", SEED)
        val midend = Midend.newGame(Mines, "", SEED)
        assertEquals(defaults.id, midend.id())
        assertEquals(defaults.state, midend.serialise().serialise())
        assertEquals("9x9n8", Mines.encodeParams(midend.params, true))
        assertEquals("9x9n8", Mines.encodeParams(midend.currentParams, true))
    }

    @Test
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/mines/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("mines", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 5, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                val midend = Midend.newGame(Mines, params, seed)
                assertEquals("golden '$params'", id, midend.id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("mines", params, seed).id,
                    midend.id(),
                )
            }
        }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = Mines.presets().map { it.params } + ""
        val seeds = listOf(
            "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
        )
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("mines", params, seed).id,
                    Midend.newGame(Mines, params, seed).id(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep pins the two prefix draws`() = OracleClient().use { oracle ->
        for (params in Mines.presets().map { it.params } + "") {
            for (seed in 0..19) {
                assertEquals(
                    "'$params' seed=$seed",
                    oracle.newGame("mines", params, seed.toString()).id,
                    Midend.newGame(Mines, params, seed.toString()).id(),
                )
            }
        }
    }

    @Test
    fun `the decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "9x9n10",
            "9",
            "+9x9",
            "9x9n1.5",
            "9x9a",
            "9x9X3Y5",
            "9x9X-1",
            "9x9XX3",
            "9x9junk",
            "300x6",
            "9x9n10aX0Y2",
        )
        for (params in accepted) {
            val expected = oracle.newGame("mines", params, SEED)
            val midend = Midend.newGame(Mines, params, SEED)
            assertEquals("id for '$params'", expected.id, midend.id())
            assertEquals(
                "canonical save for '$params'",
                expected.state,
                midend.serialise().serialise(),
            )
        }

        // The split is part of the contract: the long-term parameters
        // drop `n`, so the default preset reads `9x9n8` next to the
        // `9x9n10` of the current parameters.
        val preset = Midend.newGame(Mines, "9x9n10", SEED).serialise()
        assertEquals("9x9n8", preset.value("PARAMS"))
        assertEquals("9x9n10", preset.value("CPARAMS"))
        assertEquals("9x9n1", Midend.newGame(Mines, "9x9n1.5", SEED)
            .serialise().value("CPARAMS"))
        assertEquals("9x9n8X3Y5", Midend.newGame(Mines, "9x9X3Y5", SEED)
            .serialise().value("CPARAMS"))

        val rejected = listOf("-9x9", "9x", "9x9n-5", "9x9n", "9x9X9", "2x2")
        for (params in rejected) {
            val oracleError = try {
                oracle.newGame("mines", params, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            val message = Mines.validateParams(
                Mines.decodeParams(params),
                full = true,
            )
            assertEquals("oracle rejects '$params'", oracleError, message)
            try {
                Midend.newGame(Mines, params, SEED)
                fail("Kotlin accepted '$params'")
            } catch (e: IllegalArgumentException) {
                assertEquals("Kotlin rejects '$params'", message, e.message)
            }
        }
    }

    @Test
    fun `validation checks the C order and messages`() {
        val cases = listOf(
            "-9x9" to "Width and height must both be greater than two",
            "2x2" to "Width and height must both be greater than two",
            "0x9a" to "Width and height must both be at least one",
            "9x0a" to "Width and height must both be at least one",
            "65536x1a" to "Neither width nor height may be unreasonably large",
            "20000x20000a" to "Width times height must not be unreasonably large",
            "9x9n-5" to "Mine count may not be negative",
            "9x9n0" to "Number of mines must be greater than zero",
            "9x9n73" to "Too many mines for grid size",
            "9x9X9" to "First-click x coordinate must be inside the grid",
            "9x9Y9" to "First-click y coordinate must be inside the grid",
        )
        for ((params, message) in cases) {
            assertEquals(
                params,
                message,
                Mines.validateParams(Mines.decodeParams(params), full = true),
            )
        }
        // The short form skips the uniqueness grid check, but the mine
        // count checks still run. C is the source of truth here: a
        // short `2x2` decodes to `n = 0`, so it is rejected.
        assertEquals(
            "Number of mines must be greater than zero",
            Mines.validateParams(Mines.decodeParams("2x2"), full = false),
        )
        assertNull(Mines.validateParams(Mines.decodeParams("9x9n10"), full = true))

        // The first click may be negative (unset); only one past the
        // end is rejected, and there is no lower bound check.
        assertNull(Mines.validateParams(Mines.decodeParams("9x9X-99"), full = true))
        assertNull(Mines.validateParams(Mines.decodeParams("9x9Y-1"), full = true))
    }

    @Test
    fun `encode is full or short`() {
        val params = Mines.decodeParams("9x9n10")
        assertEquals("9x9", Mines.encodeParams(params, false))
        assertEquals("9x9n10", Mines.encodeParams(params, true))
        assertEquals("9x9", Mines.encodeParams(Mines.decodeParams("9x9n8aX3Y4"), false))
        assertEquals("9x9n8aX3Y4", Mines.encodeParams(Mines.decodeParams("9x9n8aX3Y4"), true))
        assertEquals("9x9n8", Mines.encodeParams(Mines.decodeParams("9x9X-1Y-1"), true))
    }

    @Test
    fun `the description grammar validates like C`() {
        val params = Mines.decodeParams("9x9n10")
        val valid = listOf(
            "r10,u,",
            "r10,u,zzzz",
            "r10,a,8cb2",
            "4,4,m9eed07198e89048f2e4d0",
            "4,4,u000000000000000000000",
            "m9eed07198e89048f2e4d0",
            "aeed07198e89048f2e4d0",
            "0,0,z00000000000000000000",
        )
        for (desc in valid) {
            assertNull("valid '$desc'", Mines.validateDesc(params, desc))
        }
        val invalid = mapOf(
            "r" to "No initial mine count in game description",
            "rx,u," to "No initial mine count in game description",
            "r73,u," to "Too many mines for grid size",
            "r10x,u," to "No ',' after initial x-coordinate in game description",
            "r10,x," to "No uniqueness specifier in game description",
            "r10,u" to "No ',' after uniqueness specifier in game description",
            "0;m9ee" to "No ',' after initial x-coordinate in game description",
            "0,m9ee" to "No initial y-coordinate in game description",
            "0,9,m9ee" to "Initial y-coordinate was out of range",
            "0,0m9ee" to "No ',' after initial y-coordinate in game description",
            "0,0," to "Game description is wrong length",
            "" to "Game description is wrong length",
        )
        for ((desc, message) in invalid) {
            assertEquals("invalid '$desc'", message, Mines.validateDesc(params, desc))
        }
    }

    @Test(timeout = 120_000)
    fun `a first click supersedes the description for every preset and tile`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                FirstClick("9x9n10", "12345", 4, 4, 48),
                FirstClick("9x9n10", "12345", 0, 0, 20),
                FirstClick("9x9n35", "12345", 4, 4, 63),
                FirstClick("9x9n35", "18", 0, 0, 20),
                FirstClick("16x16n40", "12345", 0, 0, 82),
                FirstClick("16x16n40", "12345", 15, 15, 100),
                FirstClick("16x16n99", "1", 8, 8, 20),
                FirstClick("9x9n10", "seed", 0, 0, 20),
                FirstClick("9x9n10", "another seed", 8, 0, 43),
                FirstClick("9x9n10", "0", 3, 5, 20),
                FirstClick("9x9n10", "-7", 5, 3, 20),
                FirstClick("9x9n10", "", 2, 7, 20),
                FirstClick("9x9n10", "12345", 6, 2, 35),
            )
            for (case in cases) {
                val expected = oracle.newGame("mines", case.params, case.seed)
                val board = Mines.computeSize(Mines.decodeParams(case.params), case.tile)
                val px = case.x * case.tile + 8 + case.tile / 2
                val py = case.y * case.tile + 8 + case.tile / 2
                val points = listOf(
                    OracleClient.Point(px, py, Buttons.LEFT_BUTTON),
                    OracleClient.Point(px, py, Buttons.LEFT_RELEASE),
                )
                val clicked = oracle.pointsSized(
                    expected.state,
                    points,
                    width = board.width,
                    height = board.height,
                )

                val midend = Midend.newGame(Mines, case.params, case.seed)
                midend.processKey(px, py, Buttons.LEFT_BUTTON, case.tile)
                midend.processKey(px, py, Buttons.LEFT_RELEASE, case.tile)

                val what = "${case.params} '${case.seed}' ${case.x},${case.y} " +
                    "tile ${case.tile}"
                assertEquals("$what id", clicked.state.id, midend.id())
                assertEquals("$what status", clicked.state.status, midend.status())
                assertEquals("$what undo", clicked.state.canUndo, midend.canUndo)
                assertEquals("$what redo", clicked.state.canRedo, midend.canRedo)
                assertEquals(
                    "$what canonical save",
                    clicked.state.state,
                    midend.serialise().serialise(),
                )
                assertEquals(
                    "$what text",
                    oracle.text(clicked.state.state),
                    midend.textFormat(),
                )

                val save = SgtpSave.parse(clicked.state.state)
                assertEquals(
                    "$what DESC",
                    save.value("DESC"),
                    midend.serialise().value("DESC"),
                )
                assertEquals(
                    "$what PRIVDESC",
                    save.value("PRIVDESC"),
                    midend.serialise().value("PRIVDESC"),
                )
                assertEquals(
                    "$what MOVE",
                    save.value("MOVE"),
                    midend.serialise().value("MOVE"),
                )
                assertTrue("$what has DESC", midend.desc.contains("m"))
                assertTrue("$what has PRIVDESC", midend.privDesc != null)
            }
        }

    @Test(timeout = 120_000)
    fun `the batch generator matches the interactive stream prefix`() {
        // The batch form is unreachable through the seam, but it consumes
        // the same two prefix draws and the same generator.
        val params = Mines.decodeParams("9x9n10") as Mines.Params
        val prefix = RandomState.fromSeed("12345")
        val x = prefix.upto(9).toInt()
        val y = prefix.upto(9).toInt()
        val batch = Mines.newBatchDesc(params, RandomState.fromSeed("12345"))
        assertNull(batch.auxInfo)
        assertTrue(batch.text, batch.text.startsWith("$x,$y,m"))

        val run = Mines.generateLayoutForSeed(params, "12345", x, y)
        assertEquals(batch.text, run.desc)
        assertEquals(1, run.attempts)
        assertEquals(10, run.mines.count { it })
    }

    @Test
    fun `a layout description loads and a non-hex bitmap is empty`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                "4x4n1:0,0,m9690",
                "4x4n1:0,0,u0004",
                "4x4n1:0,0,0004",
                "4x4n1:0,0,uzzzz",
                "9x9n10:4,4,m9eed07198e89048f2e4d0",
                "9x9n10:4,4,u000000000000000000000",
            )
            for (gameId in cases) {
                val midend = Midend.fromGameId(Mines, gameId)
                val save = midend.serialise().serialise()
                val loaded = oracle.id(save)
                assertEquals("'$gameId' id", loaded.id, midend.id())
                assertEquals("'$gameId' status", loaded.status, midend.status())
                assertEquals("'$gameId' text", oracle.text(save), midend.textFormat())
                assertEquals("'$gameId' canonical", save, loaded.state)
            }

            // 4x4 with no mines is won as soon as the first click opens
            // the safe area.
            val empty = Midend.fromGameId(Mines, "4x4n1:0,0,uzzzz")
            assertEquals(1, empty.status())
            assertEquals("COMPLETED!", Mines.statusText(empty.state, empty.ui))
            assertTrue(empty.textFormat().none { it == '?' })
        }

    @Test
    fun `a short or long bitmap is rejected like C`() = OracleClient().use { oracle ->
        val params = "4x4n1"
        val vectors = listOf(
            "0,0,m969",
            "0,0,m96900",
            "0,0,uz",
            "0,0,uzz",
            "0,0,uzzzzz",
        )
        for (desc in vectors) {
            assertEquals(
                "width for '$desc'",
                "Game description is wrong length",
                Mines.validateDesc(Mines.decodeParams(params), desc),
            )
            val save = saveText(params, desc)
            val error = try {
                oracle.id(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("oracle rejects '$desc'", "Game description in save file is invalid", error)
        }
    }

    @Test
    fun `an r description ignores the rest of the RNG text`() {
        val params = Mines.decodeParams("9x9n10")
        val a = Midend.fromGameId(Mines, "9x9n10:r10,u,")
        val b = Midend.fromGameId(Mines, "9x9n10:r10,u,zzzz")
        assertEquals("9x9:r10,u,", a.id())
        assertEquals("9x9:r10,u,zzzz", b.id())
        assertFalse(a.state === b.state)
        assertEquals(a.textFormat(), b.textFormat())
        assertNull(Mines.validateDesc(params, "r10,u,"))
    }

    private data class FirstClick(
        val params: String,
        val seed: String,
        val x: Int,
        val y: Int,
        val tile: Int,
    )

    private fun saveText(params: String, desc: String): String = SgtpSave.of(
        listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Mines.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("UI", "D0"),
            SaveRecord("TIME", "0"),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        ),
    ).serialise()

    private companion object {
        const val SEED = "12345"
    }
}
