package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: mosaic.
 *
 * Every mosaic save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes
 * a Kotlin-written save for every preset and lets C read it. The history
 * cases are built in memory, so the corpus stays frozen.
 *
 * Mosaic has no `AUXINFO`, no `UI` record, and no game preference. The
 * only split between `PARAMS` and `CPARAMS` is the aggressive flag of
 * `50x50h0`: the long-term form is `50x50` and the short-term form keeps
 * `h0`, because `encode_params` writes the flag only in the full form.
 */
class MosaicSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Mosaic.name }
                .sorted()
                .toList()
            assertEquals("six presets and the default", 7, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Mosaic, SgtpSave.parse(text))
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
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }

            val defaultSave = File(testdata, "saves/initial/mosaic/default.sgtp").readText()
            val tenSave = File(testdata, "saves/initial/mosaic/10x10.sgtp").readText()
            assertEquals("the default duplicates 10x10", defaultSave, tenSave)

            val calm = File(testdata, "saves/initial/mosaic/50x50h0.sgtp").readText()
            val calmSave = SgtpSave.parse(calm)
            assertEquals("50x50h0 PARAMS", "50x50", calmSave.value("PARAMS"))
            assertEquals("50x50h0 CPARAMS", "50x50h0", calmSave.value("CPARAMS"))
            assertFalse("50x50h0 id has no h", oracle.id(calm).id.substringBefore(':').contains('h'))
            for (name in listOf("3x3", "5x5", "10x10", "15x15", "25x25")) {
                val save = SgtpSave.parse(File(testdata, "saves/initial/mosaic/$name.sgtp").readText())
                assertEquals("$name PARAMS == CPARAMS", save.value("PARAMS"), save.value("CPARAMS"))
            }
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Mosaic.presets().map { it.params }.distinct()) {
                val midend = Midend.newGame(Mosaic, params, SEED)
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
                assertEquals(
                    "$params PARAMS",
                    Mosaic.encodeParams(midend.params, true),
                    SgtpSave.parse(text).value("PARAMS"),
                )
                assertEquals(
                    "$params CPARAMS",
                    Mosaic.encodeParams(midend.currentParams, true),
                    SgtpSave.parse(text).value("CPARAMS"),
                )
            }
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        val solveText = SgtpSave.parse(
            oracle.solve(historySave("3x3", DESC, emptyList(), 1)).state,
        ).value("SOLVE")!!
        val played = oracle.pointsSized(
            historySave("3x3", DESC, emptyList(), 1),
            listOf(
                OracleClient.Point(32, 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(32, 32, Buttons.LEFT_RELEASE),
            ),
            width = 128,
            height = 128,
        ).state.state

        val cases = listOf(
            History("played click", listOf("MOVE" to "t0,0"), 2, 0),
            History(
                "undone position with a live redo branch",
                listOf("MOVE" to "t0,0", "MOVE" to "t1,0"),
                2,
                0,
                canRedo = true,
            ),
            History("solve", listOf("SOLVE" to solveText), 2, 1),
            History("restart", listOf("MOVE" to "t0,0", "RESTART" to DESC), 3, 0),
            History("move with trailing junk", listOf("MOVE" to "t1,0junk"), 2, 0),
            History("empty move", listOf("MOVE" to ""), 2, 0),
            History("unknown move type", listOf("MOVE" to "X"), 2, 0),
            History("s move", listOf("MOVE" to "sZZ"), 2, 1),
            History("last state 7", listOf("MOVE" to "d1,0,0,0,7"), 2, 0),
            History(
                "last state -1",
                listOf("MOVE" to "d1,0,0,0,4294967295"),
                2,
                0,
            ),
        )

        assertEquals("the played click is a real save", played, historySave("3x3", DESC, listOf("MOVE" to "t0,0"), 2))

        for (case in cases) {
            val text = historySave("3x3", DESC, case.records, case.statePos)
            val midend = Midend.fromSave(Mosaic, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
            assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
            assertEquals("${case.label} state pos", case.statePos, midend.statePos)

            val expected = oracle.id(text)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", case.status, midend.status())
            assertEquals("${case.label} oracle status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertEquals("${case.label} redo", case.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
            assertEquals(
                "${case.label} C reloads the Kotlin text",
                expected.id,
                oracle.id(midend.serialise().serialise()).id,
            )
        }
    }

    @Test
    fun `a live click, solve, and restart write the oracle text`() =
        OracleClient().use { oracle ->
            val midend = Midend.fromSave(
                Mosaic,
                SgtpSave.parse(historySave("3x3", DESC, emptyList(), 1)),
            )

            assertEquals(
                name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
                midend.processKey(32, 32, Buttons.LEFT_BUTTON, TILE),
            )
            midend.processKey(32, 32, Buttons.LEFT_RELEASE, TILE)
            assertEquals(listOf("t0,0"), midend.serialise().values("MOVE"))
            assertEquals("the click adds one state", 2, midend.stateCount)
            assertEquals(
                historySave("3x3", DESC, listOf("MOVE" to "t0,0"), 2),
                midend.serialise().serialise(),
            )

            // A solve appends the shared clue board's answer.
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
    fun `the non-aggressive preset keeps its split through a save`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Mosaic, "50x50h0", SEED)
            val text = midend.serialise().serialise()
            val save = SgtpSave.parse(text)
            assertEquals("PARAMS", "50x50", save.value("PARAMS"))
            assertEquals("CPARAMS", "50x50h0", save.value("CPARAMS"))
            assertEquals("id", "50x50:${midend.desc}", midend.id())

            val loaded = Midend.fromSave(Mosaic, save)
            assertTrue("the short id is aggressive", (loaded.params as Mosaic.Params).aggressive)
            assertFalse(
                "the current parameters stay calm",
                (loaded.currentParams as Mosaic.Params).aggressive,
            )
            assertEquals("the description is unchanged", midend.desc, loaded.desc)
            assertEquals("the save is unchanged", text, loaded.serialise().serialise())

            val expected = oracle.id(text)
            assertEquals("oracle id", expected.id, loaded.id())
            assertEquals("oracle text", oracle.text(text), loaded.textFormat())
        }

    @Test
    fun `a bad description is rejected in both directions`() = OracleClient().use { oracle ->
        for (desc in listOf("4b47,", "4b47 ", "4b47{", "4B47d")) {
            val text = historySave("3x3", desc, emptyList(), 1)
            assertTrue(
                "'$desc' is rejected by C",
                try {
                    oracle.id(text)
                    false
                } catch (e: OracleException) {
                    true
                },
            )
            assertTrue(
                "'$desc' is rejected by the port",
                try {
                    Midend.fromSave(Mosaic, SgtpSave.parse(text))
                    false
                } catch (e: RuntimeException) {
                    true
                },
            )
            assertFalse(
                "'$desc' is rejected by validateDesc",
                Mosaic.validateDesc(Mosaic.decodeParams("3x3"), desc) == null,
            )
        }
    }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canRedo: Boolean = false,
    )

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val currentParams = if (params == "50x50") "50x50h0" else params
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Mosaic.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", currentParams),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val DESC = "4b47d"
    }
}
