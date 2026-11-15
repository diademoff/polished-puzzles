package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: filling.
 *
 * Every `filling` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes a
 * Kotlin-written save for every preset and lets C read it.
 *
 * The corpus has initial saves only, so the history cases are built in
 * memory and the corpus stays frozen: a played fill, an undone position
 * with a live redo branch, a solve, and a restart. A cleaning run also
 * plays the same cases through the Kotlin midend, so the records that a
 * live game writes are compared with the records that C reads.
 *
 * A filling save has no `AUXINFO` and no `UI` record, and `PARAMS` equals
 * `CPARAMS`, because `encode_params` ignores `full`.
 */
class FillingSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Filling.name }
                .sorted()
                .toList()
            assertEquals("four preset and default saves", 4, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Filling, SgtpSave.parse(text))
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
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Filling.presets().map { it.params }.distinct()) {
                val midend = Midend.newGame(Filling, params, SEED)
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
        val cases = listOf(
            History("played fill", listOf("MOVE" to "2_3"), 2, 0),
            History(
                "undone position with a live redo branch",
                listOf("MOVE" to "2_3", "MOVE" to "3_1"),
                2,
                0,
                canRedo = true,
            ),
            History("solve", listOf("SOLVE" to "s2211"), 2, 0),
            History("restart", listOf("MOVE" to "2_3", "RESTART" to DESC), 3, 0),
        )

        for (case in cases) {
            val text = historySave(case)
            val midend = Midend.fromSave(Filling, SgtpSave.parse(text))

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
    }

    @Test
    fun `a live fill and solve and restart write the oracle text`() =
        OracleClient().use { oracle ->
            val midend = Midend.fromGameId(Filling, "2x2:$DESC")
            val tile = Filling.preferredTileSize

            // A real click and a digit play the fill that the hand-built
            // `MOVE` case records.
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(32, 64, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(32, 64, '3'.code, tile))
            assertEquals(listOf("2_3"), midend.serialise().values("MOVE"))
            assertEquals("the fill adds one state", 2, midend.stateCount)
            assertEquals(
                "the live fill writes the hand-built text",
                historySave(History("played fill", listOf("MOVE" to "2_3"), 2, 0)),
                midend.serialise().serialise(),
            )

            // The solve appends the answer that C computes for the clue
            // board.
            val afterFill = midend.serialise().serialise()
            val expectedSolve = oracle.solve(afterFill)
            assertEquals(
                "the solve record",
                "s2211",
                SgtpSave.parse(expectedSolve.state).value("SOLVE"),
            )
            assertTrue(midend.solve())
            assertEquals("solve save", expectedSolve.state, midend.serialise().serialise())
            assertEquals("solve id", expectedSolve.id, midend.id())
            assertEquals("solve status", expectedSolve.status, midend.status())
            assertEquals("solve text", oracle.text(expectedSolve.state), midend.textFormat())

            // A restart replays `DESC` and keeps the whole history.
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

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canRedo: Boolean = false,
    )

    private fun historySave(case: History): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Filling.name),
            SaveRecord("PARAMS", PARAMS),
            SaveRecord("CPARAMS", PARAMS),
            SaveRecord("DESC", DESC),
            SaveRecord("NSTATES", (1 + case.records.size).toString()),
            SaveRecord("STATEPOS", case.statePos.toString()),
        )
        for ((key, value) in case.records) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val PARAMS = "2x2"
        const val DESC = "22aa"
    }
}
