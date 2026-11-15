package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Guess
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
 * Level: strict for the save text, semantic for the state. Game: guess.
 *
 * The `UI` record is guess's first real consumer of ADR 0009. Every
 * save is compared byte for byte in both directions, with the draft,
 * the holds, the legacy form without holds, and the re-read quirks of
 * the character-stream decoder.
 */
class GuessSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every guess save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Guess.name }
                .sorted()
                .toList()
            assertEquals("two presets plus the default", 3, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals("", midend.statusText())

                val save = SgtpSave.parse(text)
                assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertTrue("${file.name} UI", save.value("UI") != null)
                assertFalse(
                    "${file.name} order",
                    save.records.indexOfFirst { it.key == "UI" } >
                        save.records.indexOfFirst { it.key == "NSTATES" },
                )
            }
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Guess.presets().map { it.params }.distinct() + "c3p2g1bm") {
                val midend = Midend.newGame(Guess, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params status", midend.status(), loaded.status)
                assertEquals("$params text", "", oracle.text(text))
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
            }
        }

    @Test
    fun `a draft and holds are written and read in both directions`() =
        OracleClient().use { oracle ->
            val sequence = listOf(
                51, 51,
                Buttons.CURSOR_LEFT, Buttons.CURSOR_LEFT,
                Buttons.CURSOR_SELECT2,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_SELECT2,
            )
            val expected = oracle.keys(oracle.newGame("guess", "", "0").state, sequence)
            assertEquals("3_,3_,0,0", uiOf(expected.state))

            val midend = Midend.newGame(Guess, "", "0")
            for (key in sequence) midend.processKey(0, 0, key, TILE)
            assertEquals("3_,3_,0,0", Guess.encodeUi(midend.ui))
            assertEquals("3_,3_,0,0", midend.serialise().value("UI"))
            assertEquals("the whole save", expected.state, midend.serialise().serialise())
            assertEquals("id", expected.id, midend.id())

            // The record is between DESC and NSTATES, with no AUXINFO.
            val keys = midend.serialise().records.map { it.key }
            assertEquals(
                listOf(
                    "SAVEFILE",
                    "VERSION",
                    "GAME",
                    "PARAMS",
                    "CPARAMS",
                    "SEED",
                    "DESC",
                    "UI",
                    "NSTATES",
                    "STATEPOS",
                ),
                keys,
            )
            assertTrue(expected.state.contains("UI      :9:3_,3_,0,0\n"))

            // The other direction: a C save with a draft and holds loads
            // in Kotlin and rewrites byte for byte.
            val reloaded = Midend.fromSave(Guess, SgtpSave.parse(expected.state))
            assertEquals("3_,3_,0,0", Guess.encodeUi(reloaded.ui))
            assertEquals(expected.state, reloaded.serialise().serialise())

            // Every value is printable ASCII.
            val ui = requireNotNull(midend.serialise().value("UI"))
            assertTrue(ui.all { it.code in 32..126 })
            assertTrue(Regex("[0-9]+_?(,[0-9]+_?)*").matches(ui))
        }

    @Test
    fun `a legacy UI record loads with no holds`() = OracleClient().use { oracle ->
        val text = saveText("c3p2g1bm", "3ea7", "1,0")
        val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
        val ui = midend.ui as Guess.Ui
        assertEquals("1,0", Guess.encodeUi(ui))
        assertFalse(ui.holds[0])
        assertFalse(ui.holds[1])
        assertEquals(text, midend.serialise().serialise())
        assertEquals(oracle.id(text).state, midend.serialise().serialise())
    }

    @Test
    fun `the UI decoder re-reads like C`() = OracleClient().use { oracle ->
        // The vectors from the port specification, including the re-read
        // quirks. The oracle decodes and re-encodes the record, so its
        // canonical save shows the C result.
        val vectors = listOf(
            "0,0", "3,0", "3_,0", "1_,1_", "9,0", "-1,0", "1,-2",
            "1", "1,", "1,0,2", " 3 , 1 ", "03,01", "1junk,2",
            "10,10", "10,1", "+2,3", "2_3,1", ",3", "3 3,1", "3,,1",
        )
        for (params in listOf("c3p2g1bm", "c10p2g3bm")) {
            for (vector in vectors) {
                val text = saveText(params, descFor(params), vector)
                val expected = oracle.id(text)
                val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
                assertEquals(
                    "$params UI '$vector'",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("$params UI '$vector' id", expected.id, midend.id())
            }
        }
    }

    @Test
    fun `history records round-trip and reveal the status`() =
        OracleClient().use { oracle ->
            val defaultDesc = "2bebff56"
            val cases = listOf(
                History(
                    "one move",
                    "c6p4g10Bm",
                    defaultDesc,
                    listOf("MOVE" to "G1,1,1,1"),
                    2,
                    0,
                    canUndo = true,
                ),
                History(
                    "undo branch",
                    "c6p4g10Bm",
                    defaultDesc,
                    listOf("MOVE" to "G1,1,1,1", "MOVE" to "G2,2,2,2"),
                    2,
                    0,
                    canUndo = true,
                    canRedo = true,
                ),
                History(
                    "solve",
                    "c6p4g10Bm",
                    defaultDesc,
                    listOf("SOLVE" to "S"),
                    2,
                    -1,
                    canUndo = true,
                ),
                History(
                    "win",
                    "c6p4g10Bm",
                    defaultDesc,
                    listOf("MOVE" to "G3,3,4,1"),
                    2,
                    1,
                    canUndo = true,
                ),
                History(
                    "loss",
                    "c3p2g1bm",
                    "3ea7",
                    listOf("MOVE" to "G1,1"),
                    2,
                    -1,
                    canUndo = true,
                ),
                History(
                    "restart",
                    "c6p4g10Bm",
                    defaultDesc,
                    listOf("MOVE" to "G1,1,1,1", "RESTART" to defaultDesc),
                    3,
                    0,
                    canUndo = true,
                ),
                History(
                    "uppercase description",
                    "c3p2g1bm",
                    "E432",
                    emptyList(),
                    1,
                    0,
                ),
            )

            for (case in cases) {
                val text = historySave(case.params, case.desc, case.records, case.statePos)
                val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
                assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
                assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
                assertEquals("${case.label} state pos", case.statePos, midend.statePos)
                assertEquals("${case.label} status", case.status, midend.status())
                assertEquals("${case.label} can undo", case.canUndo, midend.canUndo)
                assertEquals("${case.label} can redo", case.canRedo, midend.canRedo)
                assertEquals("${case.label} text", "", midend.textFormat())
                assertEquals("${case.label} status text", "", midend.statusText())

                val expected = oracle.id(text)
                assertEquals("${case.label} oracle id", expected.id, midend.id())
                assertEquals("${case.label} oracle status", expected.status, midend.status())
                assertEquals("${case.label} oracle undo", expected.canUndo, midend.canUndo)
                assertEquals("${case.label} oracle redo", expected.canRedo, midend.canRedo)
                assertEquals("${case.label} oracle text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${case.label} oracle canonical text",
                    text,
                    expected.state,
                )
                assertNull("${case.label} AUXINFO", midend.serialise().value("AUXINFO"))
                assertEquals(
                    "${case.label} PARAMS",
                    midend.serialise().value("CPARAMS"),
                    midend.serialise().value("PARAMS"),
                )
            }

            // Uppercase hex is preserved in the id, not canonicalized.
            assertEquals(
                "c3p2g1bm:E432",
                Midend.fromSave(
                    Guess,
                    SgtpSave.parse(
                        historySave("c3p2g1bm", "E432", emptyList(), 1),
                    ),
                ).id(),
            )
        }

    @Test
    fun `a move after a solve or a win is rejected by both sides`() =
        OracleClient().use { oracle ->
            val afterSolve = historySave(
                "c6p4g10Bm",
                "2bebff56",
                listOf("SOLVE" to "S", "MOVE" to "G1,1,1,1"),
                3,
            )
            val afterWin = historySave(
                "c6p4g10Bm",
                "2bebff56",
                listOf("MOVE" to "G3,3,4,1", "MOVE" to "G1,1,1,1"),
                3,
            )
            for (text in listOf(afterSolve, afterWin)) {
                try {
                    oracle.id(text)
                    throw AssertionError("the oracle loaded a move after the end")
                } catch (e: OracleException) {
                    assertEquals(
                        "Save file contained an invalid move",
                        e.message,
                    )
                }
                try {
                    Midend.fromSave(Guess, SgtpSave.parse(text))
                    throw AssertionError("Kotlin loaded a move after the end")
                } catch (e: IllegalStateException) {
                    assertTrue(
                        "Kotlin message: ${e.message}",
                        e.message!!.startsWith("save file contained an invalid move: "),
                    )
                }
            }
        }

    @Test
    fun `a save without a UI record writes a fresh one`() =
        OracleClient().use { oracle ->
            val text = SgtpSave.of(
                listOf(
                    SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                    SaveRecord("VERSION", SgtpSave.VERSION),
                    SaveRecord("GAME", Guess.name),
                    SaveRecord("PARAMS", "c3p2g1bm"),
                    SaveRecord("CPARAMS", "c3p2g1bm"),
                    SaveRecord("DESC", "3ea7"),
                    SaveRecord("NSTATES", "1"),
                    SaveRecord("STATEPOS", "1"),
                ),
            ).serialise()
            val midend = Midend.fromSave(Guess, SgtpSave.parse(text))
            assertEquals("0,0", midend.serialise().value("UI"))
            // C also writes its fresh UI when it reserialises.
            assertEquals("0,0", uiOf(oracle.id(text).state))
            assertEquals("c3p2g1bm:3ea7", midend.id())
        }

    @Test
    fun `both save records keep the B b m M letters`() =
        OracleClient().use { oracle ->
            for (blank in listOf(false, true)) {
                for (multiple in listOf(false, true)) {
                    val params = "c4p4g10" + (if (blank) "b" else "B") +
                        (if (multiple) "m" else "M")
                    val midend = Midend.newGame(Guess, params, SEED)
                    val save = midend.serialise()
                    assertEquals(params, save.value("PARAMS"))
                    assertEquals(params, save.value("CPARAMS"))
                    val oracleSave = oracle.newGame("guess", params, SEED)
                    assertEquals(params, SgtpSave.parse(oracleSave.state).value("PARAMS"))
                    assertEquals(params, SgtpSave.parse(oracleSave.state).value("CPARAMS"))
                    assertEquals(oracleSave.id, midend.id())
                    assertEquals(oracleSave.state, save.serialise())
                }
            }
        }

    private data class History(
        val label: String,
        val params: String,
        val desc: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
    )

    private fun uiOf(state: String): String =
        requireNotNull(SgtpSave.parse(state).value("UI")) { "save has no UI record" }

    private fun descFor(params: String): String =
        if (params.startsWith("c3")) "3ea7" else "2be1"

    private fun saveText(params: String, desc: String, ui: String): String =
        SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", Guess.name),
                SaveRecord("PARAMS", params),
                SaveRecord("CPARAMS", params),
                SaveRecord("DESC", desc),
                SaveRecord("UI", ui),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()

    private fun historySave(
        params: String,
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Guess.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("UI", if (params.startsWith("c3")) "0,0" else "0,0,0,0"),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }
}
