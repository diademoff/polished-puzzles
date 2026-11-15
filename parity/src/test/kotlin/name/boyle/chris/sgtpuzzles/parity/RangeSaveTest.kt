package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: range.
 *
 * Every `range` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes a
 * Kotlin-written save for every preset and lets C read it. The history
 * cases are built in memory, so the corpus stays frozen.
 *
 * A range save has no `AUXINFO` and no `UI` record, and `PARAMS` equals
 * `CPARAMS`, because `encode_params` ignores `full`.
 */
class RangeSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Range.name }
                .sorted()
                .toList()
            assertEquals("four presets and the default", 5, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Range, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())

                val save = SgtpSave.parse(text)
                assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertNull("${file.name} UI", save.value("UI"))
                assertEquals(
                    "${file.name} PARAMS and CPARAMS",
                    save.value("PARAMS"),
                    save.value("CPARAMS"),
                )
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }

            val defaultSave = File(testdata, "saves/initial/range/default.sgtp").readText()
            val nineSave = File(testdata, "saves/initial/range/9x6.sgtp").readText()
            assertEquals("the default params are 9x6", defaultSave, nineSave)
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Range.presets().map { it.params }.distinct()) {
                val midend = Midend.newGame(Range, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)

                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params status", midend.status(), loaded.status)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertNull("$params AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
                assertNull("$params UI", SgtpSave.parse(text).value("UI"))
            }
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        val clicked = oracle.pointsSized(
            DEFAULT,
            listOf(OracleClient.Point(96, 32, Buttons.LEFT_BUTTON)),
        ).state.state
        val solveText = SgtpSave.parse(oracle.solve(DEFAULT).state).value("SOLVE")!!

        val cases = listOf(
            History("played click", listOf("MOVE" to "B,0,2"), 2, 0),
            History(
                "undone position with a live redo branch",
                listOf("MOVE" to "B,0,2", "MOVE" to "B,0,3"),
                2,
                0,
                canRedo = true,
            ),
            History("solve", listOf("SOLVE" to solveText), 2, 1),
            History("restart", listOf("MOVE" to "B,0,2", "RESTART" to DESC), 3, 0),
            History("move that starts with S", listOf("MOVE" to "SB,1,0"), 2, 1),
        )

        for (case in cases) {
            val text = historySave(case)
            val midend = Midend.fromSave(Range, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
            assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
            assertEquals("${case.label} state pos", case.statePos, midend.statePos)

            val expected = oracle.id(text)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", case.status, midend.status())
            assertEquals("${case.label} oracle status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertTrue("${case.label} can undo", midend.canUndo)
            assertEquals("${case.label} redo", case.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
            assertEquals(
                "${case.label} C reloads the Kotlin text",
                expected.id,
                oracle.id(midend.serialise().serialise()).id,
            )
        }

        // The clicked case is also a real pointer click on the port.
        assertEquals("a click save", clicked, historySave(cases[0]))
    }

    @Test
    fun `a live click, solve, and restart write the oracle text`() =
        OracleClient().use { oracle ->
            val midend = Midend.fromSave(Range, SgtpSave.parse(DEFAULT))
            val tile = Range.preferredTileSize

            // One left click on the empty cell (0,2) paints it black.
            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
                midend.processKey(96, 32, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(listOf("B,0,2"), midend.serialise().values("MOVE"))
            assertEquals("the click adds one state", 2, midend.stateCount)
            assertEquals(historySave(History("played click", listOf("MOVE" to "B,0,2"), 2, 0)),
                midend.serialise().serialise())

            // A solve appends the first state's answer.
            val afterClick = midend.serialise().serialise()
            val expectedSolve = oracle.solve(afterClick)
            assertTrue(midend.solve())
            assertEquals("solve save", expectedSolve.state, midend.serialise().serialise())
            assertEquals("solve id", expectedSolve.id, midend.id())
            assertEquals("solve status", expectedSolve.status, midend.status())
            assertEquals("solve text", oracle.text(expectedSolve.state), midend.textFormat())

            // A restart replays DESC and keeps the history.
            assertTrue(midend.restart())
            val restarted = midend.serialise().serialise()
            assertEquals(
                "restart records",
                listOf("MOVE", "SOLVE", "RESTART"),
                SgtpSave.parse(restarted).records
                    .filter { it.key == "MOVE" || it.key == "SOLVE" || it.key == "RESTART" }
                    .map { it.key },
            )
            val expectedRestart = oracle.id(restarted)
            assertEquals("restart id", expectedRestart.id, midend.id())
            assertEquals("restart status", expectedRestart.status, midend.status())
            assertEquals("restart text", oracle.text(restarted), midend.textFormat())
        }

    @Test
    fun `a hand-written AUXINFO round-trips and the solve ignores it`() =
        OracleClient().use { oracle ->
            // `range.c` never writes `*aux` and `solve_game` never reads
            // it, so a stray record is opaque data. `80` decodes to a
            // printable byte and re-encodes to itself for both sides.
            val text = historySave(
                History("aux", emptyList(), 1, 0),
                auxInfo = "80",
            )
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Range, SgtpSave.parse(text))
            assertEquals("aux text", text, midend.serialise().serialise())
            assertEquals("aux id", expected.id, midend.id())
            assertEquals("aux has no effect on the solve", oracle.solve(text).state,
                expectSolve(midend))

            assertTrue(midend.solve())
            assertEquals(
                "the solve is the same as without AUXINFO",
                SgtpSave.parse(oracle.solve(DEFAULT).state).value("SOLVE"),
                SgtpSave.parse(midend.serialise().serialise()).value("SOLVE"),
            )
        }

    @Test
    fun `the trailing comma and the signed char id round-trip`() =
        OracleClient().use { oracle ->
            // `validate_desc` stops at the first comma, and the id keeps
            // the stored text.
            val text = historySave(
                History("tail", emptyList(), 1, 0),
                desc = "$DESC,zzz",
            )
            val expected = oracle.id(text)
            assertEquals("9x6:$DESC,zzz", expected.id)
            val midend = Midend.fromSave(Range, SgtpSave.parse(text))
            assertEquals("tail id", expected.id, midend.id())
            assertEquals("tail text", oracle.text(text), midend.textFormat())
            assertEquals("tail canonical text", text, midend.serialise().serialise())

            // `300x6` decodes to `44x6`; the id keeps the truncated pair.
            val truncated = oracle.newGame("range", "300x6", SEED)
            val truncatedMidend = Midend.newGame(Range, "300x6", SEED)
            assertEquals("truncated id", truncated.id, truncatedMidend.id())
            assertEquals(
                "truncated PARAMS",
                SgtpSave.parse(truncated.state).value("PARAMS"),
                SgtpSave.parse(truncatedMidend.serialise().serialise()).value("PARAMS"),
            )
            assertEquals("44x6", SgtpSave.parse(truncatedMidend.serialise().serialise()).value("PARAMS"))
        }

    private fun expectSolve(midend: Midend): String {
        // The solve answer is the first state's answer whatever the aux.
        val copy = Midend.fromSave(Range, midend.serialise())
        assertTrue(copy.solve())
        return copy.serialise().serialise()
    }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canRedo: Boolean = false,
    )

    private fun historySave(
        case: History,
        auxInfo: String? = null,
        desc: String = DESC,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Range.name),
            SaveRecord("PARAMS", PARAMS),
            SaveRecord("CPARAMS", PARAMS),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
        )
        if (auxInfo != null) saveRecords += SaveRecord("AUXINFO", auxInfo)
        saveRecords += SaveRecord("NSTATES", (1 + case.records.size).toString())
        saveRecords += SaveRecord("STATEPOS", case.statePos.toString())
        for ((key, value) in case.records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "9x6"
        const val DESC = "7_5e4d5g8l7g9d11e8_13"

        val DEFAULT: String = File(
            System.getProperty("testdata") ?: error("testdata system property is not set"),
            "saves/initial/range/9x6.sgtp",
        ).readText()
    }
}
