package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * inertia.
 *
 * Every inertia save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. A Kotlin save for every
 * preset is loaded by C with the same id and canonical text.
 *
 * The history cases are built in memory: a played move, an undone
 * position with a live redo branch, a `SOLVE`, a `RESTART`, and the
 * real `icons/inertia.sav` with 20 `MOVE` records and `STATEPOS 5`.
 * The `UI D<deaths>` record is pinned for the canonical and the
 * non-canonical forms.
 */
class InertiaSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    @Test
    fun `every inertia save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == Inertia.name
                }
                .sorted()
                .toList()
            assertEquals(4, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Inertia, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} UI",
                    "D0",
                    midend.serialise().value("UI"),
                )
                assertFalse(
                    "${file.name} has no AUXINFO",
                    midend.serialise().records.any { it.key == "AUXINFO" },
                )
                assertEquals(
                    "${file.name} PARAMS equals CPARAMS",
                    midend.serialise().value("PARAMS"),
                    midend.serialise().value("CPARAMS"),
                )
                assertEquals(
                    "${file.name} id uses the short params",
                    SgtpSave.parse(text).value("PARAMS"),
                    midend.id().substringBefore(':'),
                )
            }
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Inertia.presets().map { it.params }.distinct()) {
                val midend = Midend.newGame(Inertia, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertEquals("$params status", midend.status(), loaded.status)
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertEquals("$params no AUXINFO", null, SgtpSave.parse(text).value("AUXINFO"))
            }
        }

    @Test
    fun `a played move and a live redo branch round-trip`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Inertia, "10x8", SEED)
        midend.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '2'.code, TILE)
        val moved = midend.serialise().serialise()
        assertEquals(
            "one MOVE in the saved branch",
            listOf("4"),
            midend.serialise().values("MOVE"),
        )
        assertSame("moved", oracle.id(moved), midend)

        assertTrue(midend.undo())
        val undone = midend.serialise().serialise()
        assertEquals(1, SgtpSave.parse(undone).values("MOVE").size)
        assertEquals("1", SgtpSave.parse(undone).value("STATEPOS"))
        assertEquals("2", SgtpSave.parse(undone).value("NSTATES"))
        assertSame("undone with a redo branch", oracle.id(undone), midend)
        assertTrue(midend.canRedo)

        // C reads the Kotlin file and keeps the live branch.
        val reloaded = Midend.fromSave(Inertia, SgtpSave.parse(undone))
        assertTrue(reloaded.canRedo)
        assertFalse(reloaded.canUndo)
        assertEquals(2, reloaded.stateCount)
        assertEquals(1, reloaded.statePos)

        val redone = oracle.redo(undone)
        assertTrue(midend.redo())
        assertSame("redone", redone, midend)
    }

    @Test
    fun `a SOLVE entry round-trips in both directions`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Inertia, "10x8", SEED)
        assertTrue(midend.solve())
        val text = midend.serialise().serialise()
        assertEquals(1, SgtpSave.parse(text).values("SOLVE").size)
        assertSame("solved save", oracle.id(text), midend)

        val loaded = Midend.fromSave(Inertia, SgtpSave.parse(text))
        assertSame("Kotlin reload", oracle.id(text), loaded)
        assertEquals(text, loaded.serialise().serialise())
        assertEquals("Auto-solver used. Gems: 16", loaded.statusText())

        // C also accepts the Kotlin file and follows the same hint.
        val next = oracle.keys(text, listOf(Buttons.CURSOR_SELECT))
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            loaded.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertEquals(next.state, loaded.serialise().serialise())
    }

    @Test
    fun `a RESTART entry round-trips`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Inertia, "3x2", SEED)
        midend.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '8'.code, TILE)
        assertTrue(midend.restart())
        val text = midend.serialise().serialise()
        assertEquals(1, SgtpSave.parse(text).values("RESTART").size)
        assertSame("restart save", oracle.id(text), midend)

        val loaded = Midend.fromSave(Inertia, SgtpSave.parse(text))
        assertEquals(text, loaded.serialise().serialise())
        assertSame("Kotlin reload", oracle.id(text), loaded)
        assertTrue(loaded.canUndo)
        assertFalse(loaded.canRedo)
    }

    @Test
    fun `the UI record follows the sscanf prefix in both directions`() =
        OracleClient().use { oracle ->
            val desc = "bbbbb" + "gbbbb" + "bbSbb" + "bbbbb" + "bbbbb"
            for ((ui, canonical) in listOf(
                "D0" to "D0",
                "D5" to "D5",
                "D 7" to "D7",
                "D+8" to "D8",
                "D-3" to "D-3",
                "X9" to "D0",
                "D" to "D0",
                "D12junk" to "D12",
                " D4" to "D0",
                "D004" to "D4",
            )) {
                val save = saveText("5x5", desc, emptyList(), ui)
                val expected = oracle.id(save)
                assertEquals("'$ui' C canonical", canonical, SgtpSave.parse(expected.state).value("UI"))

                val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
                assertSame("'$ui'", expected, midend)
                assertEquals("'$ui' Kotlin canonical", canonical, midend.serialise().value("UI"))
                assertEquals("'$ui' text", oracle.text(save), midend.textFormat())
            }
        }

    @Test
    fun `the real icon save keeps its history and redo branch`() =
        OracleClient().use { oracle ->
            val file = File(testdata.parentFile, "app/src/main/jni/icons/inertia.sav")
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertEquals(20, save.values("MOVE").size)
            assertEquals("5", save.value("STATEPOS"))
            assertEquals("21", save.value("NSTATES"))

            val expected = oracle.id(text)
            val midend = Midend.fromSave(Inertia, save)
            assertSame("icons save", expected, midend)
            assertEquals(oracle.text(text), midend.textFormat())
            assertTrue(midend.canUndo)
            assertTrue(midend.canRedo)
            assertEquals(5, midend.statePos)
            assertEquals(21, midend.stateCount)

            // The live redo branch advances in C and in Kotlin.
            val redone = oracle.redo(text)
            assertTrue(midend.redo())
            assertSame("icons redo", redone, midend)
            assertEquals(
                redone.state,
                midend.serialise().serialise(),
            )
        }

    @Test
    fun `hand-written history quirks load in both directions`() = OracleClient().use { oracle ->
        val open = "bbbbb" + "gbbbb" + "bbSbb" + "bbbbb" + "bbbbb"
        val cases = listOf(
            // An empty MOVE is direction 0.
            saveText("5x5", open, listOf("MOVE" to "")),
            // An S payload outside 0..7 loads; C aborts only on a select.
            saveText("5x5", open, listOf("SOLVE" to "S8")),
            // dup_game clears dead, so a SOLVE after a fatal move revives.
            saveText(
                "3x2",
                "Sgmbgs",
                listOf("MOVE" to "2", "SOLVE" to "S1375"),
            ),
            // A dead current state with a non-zero death counter.
            saveText("3x2", "Sgmwbs", listOf("MOVE" to "2"), "D2"),
        )
        for ((index, save) in cases.withIndex()) {
            val expected = oracle.id(save)
            val midend = Midend.fromSave(Inertia, SgtpSave.parse(save))
            assertSame("quirky save $index", expected, midend)
            assertEquals("quirky save $index text", oracle.text(save), midend.textFormat())
            val reloaded = oracle.id(midend.serialise().serialise())
            assertSame("quirky save $index reload", reloaded, midend)
        }
    }

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>>,
        ui: String = "D0",
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Inertia.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "UI" to ui
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
