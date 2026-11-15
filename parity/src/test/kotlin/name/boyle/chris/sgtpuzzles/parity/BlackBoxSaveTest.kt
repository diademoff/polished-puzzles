package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * blackbox.
 *
 * The six frozen initial saves round-trip byte for byte in both
 * directions. The `UI` record is the only frontend value in the file,
 * so its `sscanf` model, the missing record, and the legacy forms are
 * covered here. History cases pin the moves that allocate laser
 * numbers and flags.
 */
class BlackBoxSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == BlackBox.name }
                .sorted()
                .toList()
            assertEquals("five presets plus the default", 6, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} status text",
                    oracleStatus(oracle, text),
                    midend.statusText(),
                )

                val save = SgtpSave.parse(text)
                assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertEquals("${file.name} UI", "E0", save.value("UI"))
                assertEquals(
                    "${file.name} PARAMS and CPARAMS",
                    save.value("PARAMS"),
                    save.value("CPARAMS"),
                )
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "UI", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in BlackBox.presets().map { it.params }) {
                val midend = Midend.newGame(BlackBox, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params status", midend.status(), loaded.status)
                assertEquals("$params text", "", oracle.text(text))
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertEquals("$params UI", "E0", SgtpSave.parse(text).value("UI"))
            }
        }

    @Test
    fun `the UI record round-trips in both directions`() =
        OracleClient().use { oracle ->
            val vectors = listOf(
                "E0", "E1", "E7", "E-2", "E+4", "E 3", "E007", "E5junk",
                "E3.5", "X3", "", "E", "e5", "EE3", " E4",
            )
            for (vector in vectors) {
                val text = saveText("w5h5m3M3", "7f1b9a74d74b19f6", vector)
                val expected = oracle.id(text)
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
                assertEquals(
                    "canonical save for '$vector'",
                    expected.state,
                    midend.serialise().serialise(),
                )
                assertEquals("id for '$vector'", expected.id, midend.id())
                assertEquals(
                    "status text for '$vector'",
                    oracleStatus(oracle, expected.state),
                    midend.statusText(),
                )
                val encoded = midend.serialise().value("UI")!!
                assertTrue(
                    "encoder writes E<int> for '$vector': $encoded",
                    Regex("E-?[0-9]+").matches(encoded),
                )
            }

            // The encoder always writes the canonical form.
            assertEquals("E0", BlackBox.encodeUi(BlackBox.Ui(errors = 0)))
            assertEquals("E1", BlackBox.encodeUi(BlackBox.Ui(errors = 1)))
            assertEquals("E-2", BlackBox.encodeUi(BlackBox.Ui(errors = -2)))
        }

    @Test
    fun `a save without a UI record loads with zero attempts`() =
        OracleClient().use { oracle ->
            val text = SgtpSave.of(
                listOf(
                    SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                    SaveRecord("VERSION", SgtpSave.VERSION),
                    SaveRecord("GAME", BlackBox.name),
                    SaveRecord("PARAMS", "w5h5m3M3"),
                    SaveRecord("CPARAMS", "w5h5m3M3"),
                    SaveRecord("DESC", "7f1b9a74d74b19f6"),
                    SaveRecord("NSTATES", "1"),
                    SaveRecord("STATEPOS", "1"),
                ),
            ).serialise()
            val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
            assertEquals("E0", midend.serialise().value("UI"))
            assertEquals("Balls marked: 0 / 3", midend.statusText())
            assertEquals("E0", SgtpSave.parse(oracle.id(text).state).value("UI"))
            assertEquals("w5h5m3M3:7f1b9a74d74b19f6", midend.id())
        }

    @Test
    fun `history cases round-trip and reveal the status`() =
        OracleClient().use { oracle ->
            val desc = "7f1b9a74d74b19f6"
            val cases = listOf(
                History(
                    "one move",
                    listOf("MOVE" to "T1,1"),
                    2,
                    "E0",
                    0,
                    canUndo = true,
                ),
                History(
                    "undo branch",
                    listOf("MOVE" to "T1,1", "MOVE" to "T2,1"),
                    2,
                    "E0",
                    0,
                    canUndo = true,
                    canRedo = true,
                ),
                History(
                    "range shot",
                    listOf("MOVE" to "F0"),
                    2,
                    "E0",
                    0,
                    canUndo = true,
                ),
                History(
                    "wrong reveal",
                    listOf(
                        "MOVE" to "F0",
                        "MOVE" to "T1,1",
                        "MOVE" to "T2,1",
                        "MOVE" to "T3,1",
                        "MOVE" to "R",
                    ),
                    6,
                    "E1",
                    0,
                    canUndo = true,
                ),
                History(
                    "correct reveal",
                    listOf(
                        "MOVE" to "T1,4",
                        "MOVE" to "T4,2",
                        "MOVE" to "T2,4",
                        "MOVE" to "R",
                    ),
                    5,
                    "E0",
                    1,
                    canUndo = true,
                ),
                History(
                    "solve",
                    listOf("SOLVE" to "S"),
                    2,
                    "E0",
                    -1,
                    canUndo = true,
                ),
                History(
                    "double solve",
                    listOf("SOLVE" to "S", "SOLVE" to "S"),
                    3,
                    "E0",
                    -1,
                    canUndo = true,
                ),
                History(
                    "restart",
                    listOf("MOVE" to "T1,1", "RESTART" to desc),
                    3,
                    "E0",
                    0,
                    canUndo = true,
                ),
            )

            for (case in cases) {
                val text = historySave(desc, case.records, case.statePos, case.ui)
                val midend = Midend.fromSave(BlackBox, SgtpSave.parse(text))
                assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
                assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
                assertEquals("${case.label} state pos", case.statePos, midend.statePos)
                assertEquals("${case.label} status", case.status, midend.status())
                assertEquals("${case.label} can undo", case.canUndo, midend.canUndo)
                assertEquals("${case.label} can redo", case.canRedo, midend.canRedo)
                assertEquals("${case.label} UI", case.ui, midend.serialise().value("UI"))

                val expected = oracle.id(text)
                assertEquals("${case.label} oracle canonical text", text, expected.state)
                assertEquals("${case.label} oracle id", expected.id, midend.id())
                assertEquals("${case.label} oracle status", expected.status, midend.status())
                assertEquals("${case.label} oracle undo", expected.canUndo, midend.canUndo)
                assertEquals("${case.label} oracle redo", expected.canRedo, midend.canRedo)
                assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${case.label} status text",
                    oracleStatus(oracle, expected.state),
                    midend.statusText(),
                )
                assertNull("${case.label} AUXINFO", midend.serialise().value("AUXINFO"))
            }

            // The wrong reveal's two omitted ends are reproduced by the
            // replay, not stored in the save.
            val wrong = Midend.fromSave(
                BlackBox,
                SgtpSave.parse(
                    historySave(
                        desc,
                        listOf(
                            "MOVE" to "F0",
                            "MOVE" to "T1,1",
                            "MOVE" to "T2,1",
                            "MOVE" to "T3,1",
                            "MOVE" to "R",
                        ),
                        6,
                        "E1",
                    ),
                ),
            )
            val state = wrong.state as BlackBox.State
            assertEquals(12, state.exits[9] and BlackBox.LASER_OMITTED.inv())
            assertEquals(9, state.exits[12] and BlackBox.LASER_OMITTED.inv())
            assertEquals("E1", wrong.serialise().value("UI"))
        }

    @Test
    fun `a crafted save with an invalid move is rejected by both sides`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                // A move after a correct reveal.
                listOf(
                    "MOVE" to "T1,4",
                    "MOVE" to "T4,2",
                    "MOVE" to "T2,4",
                    "MOVE" to "R",
                    "MOVE" to "T1,1",
                ) to 6,
                // An out-of-range toggle.
                listOf("MOVE" to "T6,1") to 2,
                // An out-of-range laser.
                listOf("MOVE" to "F20") to 2,
                // A reveal before the minimum.
                listOf("MOVE" to "R") to 2,
            )
            for ((records, statePos) in cases) {
                val text = historySave("7f1b9a74d74b19f6", records, statePos, "E0")
                assertEquals(
                    "oracle message for $records",
                    "Save file contained an invalid move",
                    try {
                        oracle.id(text)
                        null
                    } catch (e: OracleException) {
                        e.message
                    },
                )
                try {
                    Midend.fromSave(BlackBox, SgtpSave.parse(text))
                    fail("Kotlin loaded $records")
                } catch (e: IllegalStateException) {
                    assertTrue(
                        "Kotlin message for $records: ${e.message}",
                        e.message!!.startsWith("save file contained an invalid move: "),
                    )
                }
            }
        }

    @Test
    fun `the frontend-only fields are not serialised`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(BlackBox, "w5h5m3M3", SEED)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(64, 32, Buttons.LEFT_BUTTON, TILE),
        )
        val ui = midend.ui as BlackBox.Ui
        assertTrue(ui.flashLaser == 1)
        assertEquals(0, ui.flashLaserno)
        midend.setCursorVisible(true)
        ui.newmove = true

        val text = midend.serialise().serialise()
        assertEquals("E0", SgtpSave.parse(text).value("UI"))
        assertEquals(text, oracle.id(text).state)

        val loaded = Midend.fromSave(BlackBox, SgtpSave.parse(text))
        val loadedUi = loaded.ui as BlackBox.Ui
        assertEquals(0, loadedUi.errors)
        assertEquals(BlackBox.LASER_EMPTY, loadedUi.flashLaserno)
        assertEquals(0, loadedUi.flashLaser)
        assertEquals(1, loadedUi.curX)
        assertEquals(1, loadedUi.curY)
        assertFalse(loadedUi.curVisible)
        assertFalse(loadedUi.newmove)
        // The move itself is in the history and the laser is replayed.
        assertEquals(listOf("F0"), SgtpSave.parse(text).values("MOVE"))
        val loadedState = loaded.state as BlackBox.State
        assertNotEquals(BlackBox.LASER_EMPTY, loadedState.exits[0])
    }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val ui: String,
        val status: Int,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
    )

    private fun oracleStatus(oracle: OracleClient, state: String): String =
        (oracle.trace(state).field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .first { it.string("op") == "status" }
            .string("text")

    private fun saveText(params: String, desc: String, ui: String): String =
        SgtpSave.of(
            listOf(
                SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                SaveRecord("VERSION", SgtpSave.VERSION),
                SaveRecord("GAME", BlackBox.name),
                SaveRecord("PARAMS", params),
                SaveRecord("CPARAMS", params),
                SaveRecord("DESC", desc),
                SaveRecord("UI", ui),
                SaveRecord("NSTATES", "1"),
                SaveRecord("STATEPOS", "1"),
            ),
        ).serialise()

    private fun historySave(
        desc: String,
        records: List<Pair<String, String>>,
        statePos: Int,
        ui: String,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", BlackBox.name),
            SaveRecord("PARAMS", "w5h5m3M3"),
            SaveRecord("CPARAMS", "w5h5m3M3"),
            SaveRecord("DESC", desc),
            SaveRecord("UI", ui),
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
