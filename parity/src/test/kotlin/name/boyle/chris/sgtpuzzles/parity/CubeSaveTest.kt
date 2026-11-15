package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Midend
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
 * Level: strict for the save text, semantic for the state. Game: cube.
 *
 * The five frozen initial saves round-trip byte for byte. A cube save
 * has no `AUXINFO` and no `UI` record, and `PARAMS` equals `CPARAMS`.
 * The quirks are C behaviour and are pinned, not "fixed": an
 * out-of-range index clamps to zero, spare bits are ignored, lowercase
 * hex is preserved in the description text, and the description is not
 * canonicalised in the id.
 */
class CubeSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Cube.name }
                .sorted()
                .toList()
            assertEquals("five preset and default saves", 5, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Cube, SgtpSave.parse(text))
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
    fun `a Kotlin save for every preset and extra vector loads in the oracle`() =
        OracleClient().use { oracle ->
            val paramsList = Cube.presets().map { it.params } +
                listOf("c4x5", "c5x4", "c2x4", "c4x2", "t2x2", "t0x3", "o2x3", "i2x2")
            for (params in paramsList) {
                val midend = Midend.newGame(Cube, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params status", midend.status(), loaded.status)
                assertEquals("$params text", "", oracle.text(text))
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertEquals("$params PARAMS", params, SgtpSave.parse(text).value("PARAMS"))
                assertEquals("$params CPARAMS", params, SgtpSave.parse(text).value("CPARAMS"))
                assertNull("$params AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
                assertNull("$params UI", SgtpSave.parse(text).value("UI"))
            }
        }

    @Test
    fun `history cases round-trip in both directions`() = OracleClient().use { oracle ->
        val cases = listOf(
            History("one move", listOf("MOVE" to "U"), 2, 0, canUndo = true),
            History(
                "undo branch",
                listOf("MOVE" to "U", "MOVE" to "R"),
                2,
                0,
                canUndo = true,
                canRedo = true,
            ),
            History("win", WIN.map { "MOVE" to it }, 14, 1, canUndo = true),
            History("post-win", (WIN + "U").map { "MOVE" to it }, 15, 1, canUndo = true),
            History("restart", listOf("MOVE" to "U", "RESTART" to DESC), 3, 0, canUndo = true),
        )
        for (case in cases) {
            val text = historySave(case.records, case.statePos)
            val midend = Midend.fromSave(Cube, SgtpSave.parse(text))
            val expected = oracle.id(text)
            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
            assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
            assertEquals("${case.label} state pos", case.statePos, midend.statePos)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", case.status, midend.status())
            assertEquals("${case.label} can undo", case.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", case.canRedo, midend.canRedo)
            assertEquals("${case.label} status text", statusOf(oracle, text), midend.statusText())
            assertNull("${case.label} AUXINFO", midend.serialise().value("AUXINFO"))
            assertNull("${case.label} UI", midend.serialise().value("UI"))
        }

        assertEquals("COMPLETED! Moves: 13", statusOf(oracle, historySave(WIN.map { "MOVE" to it }, 14)))
        assertEquals(
            "COMPLETED! Moves: 13",
            statusOf(oracle, historySave((WIN + "U").map { "MOVE" to it }, 15)),
        )
    }

    @Test
    fun `description quirks load in both directions`() = OracleClient().use { oracle ->
        val cube = Cube.decodeParams("c4x4") as Cube.Params
        val quirks = listOf(
            "c4x4" to "205E,99",
            "c4x4" to "205E,2147483647",
            "c4x4" to "205E,2147483648",
            "c4x4" to "205E,99999999999999999999",
            "c4x4" to "205E,2",
            "c4x4" to "0000,0",
            "c4x4" to "205e,4",
            "t1x2" to "4257,0",
            "t1x2" to "4250,0",
        )
        for ((params, desc) in quirks) {
            val text = saveText(params, desc)
            val midend = Midend.fromSave(Cube, SgtpSave.parse(text))
            val expected = oracle.id(text)
            assertEquals("'$params:$desc' canonical text", text, midend.serialise().serialise())
            assertEquals("'$params:$desc' id preserves the text", "$params:$desc", midend.id())
            assertEquals("'$params:$desc' oracle id", expected.id, midend.id())
            assertEquals("'$params:$desc' status", expected.status, midend.status())
        }

        // An index above the last square clamps to zero.
        val clamped = Cube.newState(cube, "205E,99") as Cube.State
        assertEquals(0, clamped.current)
        // Spare low bits of the last character are ignored.
        assertEquals(
            Cube.newState(Cube.decodeParams("t1x2"), "4250,0"),
            Cube.newState(Cube.decodeParams("t1x2"), "4257,0"),
        )
        // An out-of-range index may still name a blue square.
        assertEquals(2, (Cube.newState(cube, "205E,2") as Cube.State).current)
        assertTrue((Cube.newState(cube, "205E,2") as Cube.State).bluemask[2])

        // The out-of-range integer forms produce the same state as zero.
        assertEquals(
            Cube.newState(cube, "205E,0"),
            Cube.newState(cube, "205E,2147483648"),
        )
        assertEquals(
            Cube.newState(cube, "205E,0"),
            Cube.newState(cube, "205E,99999999999999999999"),
        )
        // Lowercase hex is the same state, and the id keeps the text.
        assertEquals(
            Cube.newState(cube, "205E,4"),
            Cube.newState(cube, "205e,4"),
        )
    }

    @Test
    fun `malformed descriptions and moves are rejected by both sides`() =
        OracleClient().use { oracle ->
            for (desc in listOf("205E,", "205E,4x", "205E,4,5", "205E,+4", "205,4", "205G,4")) {
                val text = saveText("c4x4", desc)
                assertEquals(
                    "oracle message for '$desc'",
                    "Game description in save file is invalid",
                    try {
                        oracle.id(text)
                        null
                    } catch (e: OracleException) {
                        e.message
                    },
                )
                try {
                    Midend.fromSave(Cube, SgtpSave.parse(text))
                    fail("Kotlin loaded '$desc'")
                } catch (e: IllegalStateException) {
                    assertTrue(
                        "Kotlin message for '$desc': ${e.message}",
                        e.message!!.startsWith("game description: "),
                    )
                }
            }

            for (move in listOf("X", "l", "")) {
                val text = historySave(listOf("MOVE" to move), 2)
                assertEquals(
                    "oracle move message for '$move'",
                    "Save file contained an invalid move",
                    try {
                        oracle.id(text)
                        null
                    } catch (e: OracleException) {
                        e.message
                    },
                )
                try {
                    Midend.fromSave(Cube, SgtpSave.parse(text))
                    fail("Kotlin loaded move '$move'")
                } catch (e: IllegalStateException) {
                    assertTrue(
                        "Kotlin move message for '$move': ${e.message}",
                        e.message!!.startsWith("save file contained an invalid move: "),
                    )
                }
            }
        }

    @Test
    fun `the solve operation is unavailable`() = OracleClient().use { oracle ->
        val save = oracle.newGame("cube", "", SEED).state
        assertEquals(
            "This game does not support the Solve operation",
            try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            },
        )
        val midend = Midend.fromSave(Cube, SgtpSave.parse(save))
        assertFalse("Kotlin has no solver", Cube.canSolve)
        assertFalse("Midend.solve returns false", midend.solve())
        assertEquals("one state", 1, midend.stateCount)
        assertEquals("", midend.textFormat())
        assertFalse("cube cannot format as text", Cube.canFormatAsText)
    }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canUndo: Boolean,
        val canRedo: Boolean = false,
    )

    private fun statusOf(oracle: OracleClient, save: String): String =
        (oracle.trace(save).field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .filter { it.string("op") == "status" }
            .also { assertEquals("one status line", 1, it.size) }
            .single()
            .string("text")

    private fun saveText(params: String, desc: String): String = SgtpSave.of(
        listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Cube.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        ),
    ).serialise()

    private fun historySave(records: List<Pair<String, String>>, statePos: Int): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Cube.name),
            SaveRecord("PARAMS", "c4x4"),
            SaveRecord("CPARAMS", "c4x4"),
            SaveRecord("DESC", DESC),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC = "205E,4"
        val WIN = listOf("U", "R", "R", "D", "D", "R", "L", "D", "L", "L", "R", "U", "D")
    }
}
