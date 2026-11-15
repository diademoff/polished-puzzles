package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * netslide.
 *
 * Every `netslide` save in the corpus is read with `Midend.fromSave`
 * and rewritten byte for byte, and the oracle compares the id, status,
 * undo, redo, text, and `AUXINFO`. A Kotlin save for every preset and
 * for a wrapping, barrier, and move-target parameter set loads in C with
 * the same id, canonical text, and `AUXINFO`.
 *
 * A row slide, a column slide, an identity `|d| = w` move, an undone
 * position with a live redo branch, a `SOLVE`, and a `RESTART` are built
 * in memory and round-tripped in both directions. The `full` split, the
 * `AUXINFO` lengths, and the absence of `UI`, `PRIVDESC`, and `TIME` are
 * pinned.
 *
 * The move grammar is loose on purpose: `R0,3` is the identity on a
 * 3-wide board but still counts one move, and it must survive a save
 * round trip. No golden or corpus file is regenerated.
 */
class NetslideSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every corpus save round-trips byte for byte with the same AUXINFO`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Netslide.name }
                .sorted()
                .toList()
            assertEquals("ten preset and default saves", 10, files.size)

            for (file in files) {
                val text = file.readText()
                val parsed = SgtpSave.parse(text)
                val midend = Midend.fromSave(Netslide, parsed)
                val expected = oracle.id(text)
                val label = file.name

                assertEquals("$label canonical text", text, midend.serialise().serialise())
                assertEquals("$label id", expected.id, midend.id())
                assertEquals("$label status", expected.status, midend.status())
                assertEquals("$label can undo", expected.canUndo, midend.canUndo)
                assertEquals("$label can redo", expected.canRedo, midend.canRedo)
                assertEquals("$label text", oracle.text(text), midend.textFormat())

                val written = midend.serialise()
                assertEquals("$label AUXINFO", parsed.value("AUXINFO"), written.value("AUXINFO"))
                assertEquals(
                    "$label AUXINFO matches the oracle",
                    SgtpSave.parse(expected.state).value("AUXINFO"),
                    written.value("AUXINFO"),
                )
                assertNull("$label UI", written.value("UI"))
                assertNull("$label PRIVDESC", written.value("PRIVDESC"))
                assertNull("$label TIME", written.value("TIME"))
                assertEquals(
                    "$label record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
                    ),
                    parsed.records.map { it.key },
                )
            }
        }

    @Test
    fun `a Kotlin save for every preset and extra vector loads in C`() =
        OracleClient().use { oracle ->
            val paramsList = Netslide.presets().map { it.params } + listOf(
                "4x4m10",
                "3x3b0.5",
                "3x3b0.9999",
                "3x3wm2",
                "3x3m5",
                "4x7",
                "2x2",
            )
            for (params in paramsList) {
                val midend = Midend.newGame(Netslide, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)

                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params status", midend.status(), loaded.status)
                assertEquals("$params text", "", oracle.text(text))
                assertEquals(
                    "$params AUXINFO",
                    SgtpSave.parse(text).value("AUXINFO"),
                    SgtpSave.parse(loaded.state).value("AUXINFO"),
                )
                val state = midend.state as Netslide.State
                assertEquals(
                    "$params AUXINFO length",
                    2 * (state.width * state.height + 1),
                    SgtpSave.parse(text).value("AUXINFO")!!.length,
                )
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertNull("$params UI", SgtpSave.parse(text).value("UI"))
                assertNull("$params PRIVDESC", SgtpSave.parse(text).value("PRIVDESC"))
            }
        }

    @Test
    fun `the barrier rate stays out of the short id and out of PARAMS`() {
        val easy = Midend.newGame(Netslide, "3x3b1", SEED)
        val easySave = easy.serialise()
        assertEquals("3x3", easySave.value("PARAMS"))
        assertEquals("3x3b1", easySave.value("CPARAMS"))
        assertTrue("the id has no barrier rate", easy.id().startsWith("3x3:"))
        assertFalse("the id has no 'b'", easy.id().substringBefore(':').contains('b'))

        val target = Midend.newGame(Netslide, "3x3m5", SEED).serialise()
        assertEquals("3x3m5", target.value("PARAMS"))
        assertEquals("3x3m5", target.value("CPARAMS"))

        val wrapping = Midend.newGame(Netslide, "3x3wm2", SEED).serialise()
        assertEquals("3x3wm2", wrapping.value("PARAMS"))
        assertEquals("3x3wm2", wrapping.value("CPARAMS"))
    }

    @Test
    fun `in-memory histories round-trip in both directions`() = OracleClient().use { oracle ->
        val auxInfo = SgtpSave.parse(oracle.newGame("netslide", "3x3", SEED).state)
            .value("AUXINFO")!!
        val cases = listOf(
            History("row slide", listOf("MOVE" to "R0,1"), 2, 0, canUndo = true),
            History("column slide", listOf("MOVE" to "C0,1"), 2, 0, canUndo = true),
            History("identity full cycle", listOf("MOVE" to "R0,3"), 2, 0, canUndo = true),
            History(
                "undo with a live redo branch",
                listOf("MOVE" to "R0,1", "MOVE" to "C0,1"),
                2,
                0,
                canUndo = true,
                canRedo = true,
            ),
            History("solve", listOf("SOLVE" to "S15c9d6234"), 2, 1, canUndo = true),
            History(
                "restart",
                listOf("MOVE" to "R0,1", "RESTART" to BASE_DESC),
                3,
                0,
                canUndo = true,
            ),
        )

        for (case in cases) {
            val text = historyText(case.records, case.statePos, auxInfo)
            val midend = Midend.fromSave(Netslide, SgtpSave.parse(text))
            val expected = oracle.id(text)
            val label = case.label

            assertEquals("$label canonical text", text, midend.serialise().serialise())
            assertEquals("$label state count", case.records.size + 1, midend.stateCount)
            assertEquals("$label state pos", case.statePos, midend.statePos)
            assertEquals("$label id", expected.id, midend.id())
            assertEquals("$label status", case.status, midend.status())
            assertEquals("$label C status", expected.status, midend.status())
            assertEquals("$label can undo", case.canUndo, midend.canUndo)
            assertEquals("$label can redo", case.canRedo, midend.canRedo)
            assertEquals("$label C can redo", expected.canRedo, midend.canRedo)
            assertEquals("$label status text", statusOf(oracle, text), midend.statusText())
            assertEquals(
                "$label AUXINFO",
                auxInfo,
                midend.serialise().value("AUXINFO"),
            )

            // The Kotlin text loads in C with the same state and id.
            val reloaded = oracle.id(midend.serialise().serialise())
            assertEquals("$label C id of the Kotlin text", midend.id(), reloaded.id)
            assertEquals("$label C text of the Kotlin text", text, reloaded.state)

            if (case.records.singleOrNull()?.second == "R0,3") {
                val initial = Midend.fromSave(Netslide, SgtpSave.parse(historyText(emptyList(), 1, auxInfo)))
                assertEquals(
                    "$label keeps the board",
                    (initial.state as Netslide.State).tiles.toList(),
                    (midend.state as Netslide.State).tiles.toList(),
                )
                assertEquals("$label counts one move", 1, (midend.state as Netslide.State).moveCount)
            }
        }
    }

    @Test
    fun `a live Kotlin history loads in the oracle`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Netslide, "3x3b1", SEED)

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(24, 72, Buttons.LEFT_BUTTON, 48),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(72, 24, Buttons.LEFT_BUTTON, 48),
        )
        var loaded = oracle.id(midend.serialise().serialise())
        assertEquals("two-sided history id", midend.id(), loaded.id)
        assertEquals("two-sided history text", midend.serialise().serialise(), loaded.state)
        assertEquals("two-sided history status", midend.status(), loaded.status)
        assertEquals("two-sided history undo", midend.canUndo, loaded.canUndo)
        assertEquals("two-sided history redo", midend.canRedo, loaded.canRedo)
        assertEquals(
            "the two-sided history has two moves",
            2,
            midend.serialise().values("MOVE").size,
        )

        // Undo leaves a live redo branch in both directions.
        assertTrue(midend.undo())
        assertTrue(midend.canRedo)
        loaded = oracle.id(midend.serialise().serialise())
        assertEquals("undo id", midend.id(), loaded.id)
        assertEquals("undo text", midend.serialise().serialise(), loaded.state)
        assertTrue("C sees the redo branch", loaded.canRedo)
        assertEquals("undo state pos", 2, midend.statePos)

        assertTrue(midend.redo())
        loaded = oracle.id(midend.serialise().serialise())
        assertEquals("redo id", midend.id(), loaded.id)
        assertEquals("redo text", midend.serialise().serialise(), loaded.state)
        assertFalse("C sees no redo after redo", loaded.canRedo)

        // A solve and a restart each append one record.
        val solved = Midend.newGame(Netslide, "3x3b1", SEED)
        assertTrue(solved.solve())
        val solvedText = solved.serialise().serialise()
        assertEquals("solve record", "S" + BASE_AUX, solved.serialise().value("SOLVE"))
        assertEquals("solve C id", solved.id(), oracle.id(solvedText).id)
        assertEquals("solve C text", solvedText, oracle.id(solvedText).state)
        assertEquals("solve status", 1, oracle.id(solvedText).status)

        val restarted = Midend.newGame(Netslide, "3x3b1", SEED)
        assertEquals(
            KeyOutcome.SomeEffect,
            restarted.processKey(24, 72, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(restarted.restart())
        val restartedText = restarted.serialise().serialise()
        assertEquals("restart record", restarted.desc, restarted.serialise().value("RESTART"))
        assertEquals("restart C id", restarted.id(), oracle.id(restartedText).id)
        assertEquals("restart C text", restartedText, oracle.id(restartedText).state)
        assertEquals("restart move count", 0, (restarted.state as Netslide.State).moveCount)
    }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canUndo: Boolean,
        val canRedo: Boolean = false,
    )

    private fun historyText(
        records: List<Pair<String, String>>,
        statePos: Int,
        auxInfo: String,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Netslide.name),
            SaveRecord("PARAMS", "3x3"),
            SaveRecord("CPARAMS", "3x3"),
            SaveRecord("DESC", BASE_DESC),
            SaveRecord("AUXINFO", auxInfo),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        for ((key, value) in records) saveRecords += SaveRecord(key, value)
        return SgtpSave.of(saveRecords).serialise()
    }

    private fun statusOf(oracle: OracleClient, save: String): String =
        (oracle.trace(save).field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .filter { it.string("op") == "status" }
            .also { assertEquals("one status line", 1, it.size) }
            .single()
            .string("text")

    private companion object {
        const val SEED = "12345"
        const val BASE_DESC = "21c4d5963"
        const val BASE_AUX = "15c9d6234"
    }
}
