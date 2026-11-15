package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: unruly.
 *
 * Every `unruly` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes a
 * Kotlin-written save for every preset and lets C read it.
 *
 * `PARAMS` is written with the short form, so it drops `d<c>`; `CPARAMS`
 * keeps it. The unique flag is in both. There is no `AUXINFO` and no `UI`
 * record, and a hand-written `AUXINFO` must round-trip and must not
 * change the solve.
 */
class UnrulySaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Unruly.name }
                .sorted()
                .toList()
            assertEquals("eight unruly saves", 8, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Unruly, SgtpSave.parse(text))
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
                assertEquals(
                    "${file.name} long-term diff is dropped",
                    "dt",
                    save.value("PARAMS")!!.takeLast(2),
                )
            }

            // The golden split: `8x8dn` is `8x8dt` long-term and `8x8dn`
            // short-term, and the id carries no difficulty.
            val named = SgtpSave.parse(File(testdata, "saves/initial/unruly/8x8dn.sgtp").readText())
            assertEquals("8x8dt", named.value("PARAMS"))
            assertEquals("8x8dn", named.value("CPARAMS"))
            assertEquals(
                "8x8:bEACHcBdFEaleAbbc",
                Midend.fromSave(Unruly, named).id(),
            )
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (preset in Unruly.presets()) {
                val midend = Midend.newGame(Unruly, preset.params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)

                assertEquals("${preset.params} id", midend.id(), loaded.id)
                assertEquals("${preset.params} canonical text", text, loaded.state)
                assertEquals("${preset.params} status", midend.status(), loaded.status)
                assertEquals("${preset.params} text", midend.textFormat(), oracle.text(text))
                assertFalse("${preset.params} starts with no undo", loaded.canUndo)
                assertFalse("${preset.params} starts with no redo", loaded.canRedo)
                assertNull("${preset.params} AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
                assertNull("${preset.params} UI", SgtpSave.parse(text).value("UI"))
                assertEquals(
                    "${preset.params} CPARAMS is the full form",
                    preset.params,
                    SgtpSave.parse(text).value("CPARAMS"),
                )
            }

            // The unique flag is in the short form too, so both records
            // are `8x8udt` and the id starts with `8x8u:`.
            val unique = Midend.newGame(Unruly, "8x8u", SEED)
            val uniqueSave = SgtpSave.parse(unique.serialise().serialise())
            assertEquals("8x8udt", uniqueSave.value("PARAMS"))
            assertEquals("8x8udt", uniqueSave.value("CPARAMS"))
            assertTrue(unique.id().startsWith("8x8u:"))

            val uniqueFull = Midend.newGame(Unruly, "8x8udn", SEED)
            val uniqueFullSave = SgtpSave.parse(uniqueFull.serialise().serialise())
            assertEquals("8x8udt", uniqueFullSave.value("PARAMS"))
            assertEquals("8x8udn", uniqueFullSave.value("CPARAMS"))
            assertEquals(
                "8x8u:AjBbFgCbhafIFb",
                uniqueFull.id(),
            )
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        val cases = listOf(
            History("played move", listOf("MOVE" to "P1,0,0"), 2, 0),
            History(
                "undone position with a live redo branch",
                listOf("MOVE" to "P1,0,0", "MOVE" to "P0,0,0"),
                2,
                0,
                canRedo = true,
            ),
            History("solve", listOf("SOLVE" to SOLVE_8X8), 2, 1),
            History(
                "restart",
                listOf("MOVE" to "P1,0,0", "RESTART" to DESC_8X8),
                3,
                0,
            ),
            History(
                "move text with junk is stored verbatim",
                listOf("MOVE" to "P1,0,0junk"),
                2,
                0,
            ),
        )

        for (case in cases) {
            val text = historySave(case)
            val midend = Midend.fromSave(Unruly, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
            assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
            assertEquals("${case.label} state pos", case.statePos, midend.statePos)

            val expected = oracle.id(text)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", case.status, midend.status())
            assertEquals("${case.label} oracle status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
            assertEquals(
                "${case.label} C reloads the Kotlin text",
                expected.id,
                oracle.id(midend.serialise().serialise()).id,
            )
        }
    }

    @Test
    fun `aux info round-trips and is ignored by the solve`() = OracleClient().use { oracle ->
        // An `AUXINFO` value is the obfuscated hex form, not plain text.
        // Take a real record from a game that generates one, so the
        // decoded bytes are ASCII and the Kotlin re-encode is exact.
        val foreign = oracle.newGame("netslide", "3x3b1", SEED)
        val aux = SgtpSave.parse(foreign.state).value("AUXINFO")!!
        val text = historySave(History("aux", emptyList(), 1, 0), auxInfo = aux)
        val midend = Midend.fromSave(Unruly, SgtpSave.parse(text))

        assertEquals("AUXINFO round-trips", text, midend.serialise().serialise())
        assertEquals(aux, SgtpSave.parse(midend.serialise().serialise()).value("AUXINFO"))

        // C recomputes the answer and ignores the record, and so does
        // the port.
        val expectedSolve = oracle.solve(text)
        assertEquals(
            "C ignores AUXINFO",
            SOLVE_8X8,
            SgtpSave.parse(expectedSolve.state).value("SOLVE"),
        )
        assertEquals("Kotlin ignores AUXINFO", SOLVE_8X8, Unruly.solve(midend.state, aux))
        assertTrue(midend.solve())
        assertEquals(
            "solve save with AUXINFO",
            expectedSolve.state,
            midend.serialise().serialise(),
        )
    }

    @Test
    fun `a live click, undo, redo, and restart write the oracle text`() =
        OracleClient().use { oracle ->
            val midend = Midend.fromSave(
                Unruly,
                SgtpSave.parse(historySave(History("start", emptyList(), 1, 0))),
            )
            val tile = 48

            // One real click on cell (0,0).
            assertEquals(
                KeyOutcome.SomeEffect,
                midend.processKey(24, 24, Buttons.LEFT_BUTTON, tile),
            )
            assertEquals(
                "the live click writes the hand-built text",
                historySave(History("played move", listOf("MOVE" to "P1,0,0"), 2, 0)),
                midend.serialise().serialise(),
            )

            // Undo leaves a live redo branch.
            assertTrue(midend.undo())
            val undone = historySave(
                History("undo", listOf("MOVE" to "P1,0,0"), 1, 0, canRedo = true),
            )
            assertEquals("undo text", undone, midend.serialise().serialise())
            assertEquals("undo id", oracle.id(undone).id, midend.id())

            // Redo returns to the played move.
            assertTrue(midend.redo())
            assertEquals(
                historySave(History("redo", listOf("MOVE" to "P1,0,0"), 2, 0)),
                midend.serialise().serialise(),
            )

            // The solve record is C's.
            val beforeSolve = midend.serialise().serialise()
            assertTrue(midend.solve())
            assertEquals(
                "solve save",
                oracle.solve(beforeSolve).state,
                midend.serialise().serialise(),
            )

            // A restart appends the description.
            assertTrue(midend.restart())
            val restarted = midend.serialise().serialise()
            val expectedRestart = oracle.id(restarted)
            assertEquals("restart id", expectedRestart.id, midend.id())
            assertEquals("restart status", expectedRestart.status, 0)
            assertEquals("restart text", oracle.text(restarted), midend.textFormat())
            assertEquals(
                "restart records",
                listOf("MOVE", "SOLVE", "RESTART"),
                SgtpSave.parse(restarted).records
                    .filter { it.key == "MOVE" || it.key == "SOLVE" || it.key == "RESTART" }
                    .map { it.key },
            )
            assertNull("no UI record", SgtpSave.parse(restarted).value("UI"))
        }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canRedo: Boolean = false,
    )

    private fun historySave(case: History, auxInfo: String? = null): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Unruly.name),
            SaveRecord("PARAMS", "8x8dt"),
            SaveRecord("CPARAMS", "8x8dt"),
            SaveRecord("DESC", DESC_8X8),
        )
        if (auxInfo != null) records += SaveRecord("AUXINFO", auxInfo)
        records += SaveRecord("NSTATES", (1 + case.records.size).toString())
        records += SaveRecord("STATEPOS", case.statePos.toString())
        for ((key, value) in case.records) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_8X8 = "bEACHcBdBDEalcbAbbBa"
        const val SOLVE_8X8 =
            "S1001001101101100001100111100110011010010001101011010110001001011"
    }
}
