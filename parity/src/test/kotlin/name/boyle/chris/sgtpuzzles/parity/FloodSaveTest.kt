package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: flood.
 *
 * Every `flood` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes
 * a Kotlin-written save for every preset and lets C read it.
 *
 * The last test pins the parameter asymmetry that upstream has. Flood's
 * short form is `%dx%d`, so a game id cannot carry colours or leniency:
 * the id is `12x12:...`, `PARAMS` is the short form re-encoded onto the
 * defaults (`12x12c6m5`), and only `CPARAMS` keeps the colours the user
 * chose. This is upstream behaviour and must not be "fixed".
 */
class FloodSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every flood save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val saves = File(testdata, "saves")
            val files = saves.walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == Flood.name
                }
                .sorted()
                .toList()
            assertTrue("no flood saves in the corpus", files.isNotEmpty())

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Flood, SgtpSave.parse(text))

                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
            }
        }

    @Test
    fun `a Kotlin save loads in the oracle`() = OracleClient().use { oracle ->
        for (params in Flood.presets().map { it.params }.distinct() + "4x4c3m0") {
            val midend = Midend.newGame(Flood, params, SEED)
            val text = midend.serialise().serialise()
            val loaded = oracle.id(text)

            assertEquals("$params id", midend.id(), loaded.id)
            assertEquals("$params canonical text", text, loaded.state)
            assertEquals("$params text", midend.textFormat(), oracle.text(text))
            assertEquals("$params status", midend.status(), loaded.status)
            assertFalse("$params starts with no undo", loaded.canUndo)
        }
    }

    @Test
    fun `a params string without colours keeps them out of the id`() =
        OracleClient().use { oracle ->
            // `8x8` omits both `c` and `m`. The decoder starts from the
            // defaults, so the full form is `8x8c6m5`, but the id is
            // built from the short form and cannot express them.
            val midend = Midend.newGame(Flood, "8x8", SEED)
            val save = midend.serialise()

            assertEquals("PARAMS", "8x8c6m5", save.value("PARAMS"))
            assertEquals("CPARAMS", "8x8c6m5", save.value("CPARAMS"))
            assertTrue("the id uses the short form", midend.id().startsWith("8x8:"))
            assertFalse(
                "the id cannot carry colours or leniency",
                midend.id().startsWith("8x8c"),
            )

            val text = save.serialise()
            val expected = oracle.newGame("flood", "8x8", SEED)
            assertEquals("the oracle generated the same save", expected.state, text)
            assertEquals("the oracle generated the same id", expected.id, midend.id())
            assertEquals("the oracle reads the Kotlin save", expected.id, oracle.id(text).id)
        }

    @Test
    fun `explicit colours survive only in CPARAMS`() = OracleClient().use { oracle ->
        // The long-term params come from the short form, so the id and
        // PARAMS fall back to 6 colours and 5 extra moves. CPARAMS is
        // the only record that keeps the requested `c3m0`.
        val midend = Midend.newGame(Flood, "12x12c3m0", SEED)
        val save = midend.serialise()

        assertEquals("PARAMS", "12x12c6m5", save.value("PARAMS"))
        assertEquals("CPARAMS", "12x12c3m0", save.value("CPARAMS"))
        assertTrue("the id uses the short form", midend.id().startsWith("12x12:"))
        assertFalse(
            "the id cannot carry colours or leniency",
            midend.id().startsWith("12x12c"),
        )

        val text = save.serialise()
        val expected = oracle.newGame("flood", "12x12c3m0", SEED)
        assertEquals("the oracle generated the same save", expected.state, text)
        assertEquals("the oracle generated the same id", expected.id, midend.id())
        assertEquals("the oracle reads the Kotlin save", expected.id, oracle.id(text).id)

        // Loading the id loses the colours, exactly like C.
        val fromId = Midend.fromGameId(Flood, midend.id())
        assertEquals(Flood.Params(12, 12, 6, 5), fromId.currentParams)
    }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        // The corpus has initial saves only. Build the history cases
        // here, so the corpus format stays frozen: a played move, an
        // undone position with a live redo branch, a solve, and a
        // restart.
        val params = "4x4c3m0"
        val desc = "1112101220200112,4"
        val cases = listOf(
            History("one move", listOf("MOVE" to "M2"), 2),
            History(
                "undone position",
                listOf("MOVE" to "M2", "MOVE" to "M0"),
                1,
            ),
            History("solve", listOf("SOLVE" to "S2,0,1,2"), 2),
            History(
                "restart",
                listOf("MOVE" to "M2", "RESTART" to desc),
                3,
            ),
        )

        for (case in cases) {
            val text = historySave(params, desc, case)
            val midend = Midend.fromSave(Flood, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())

            val expected = oracle.id(text)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())

            val rewritten = midend.serialise().serialise()
            assertEquals("${case.label} C reloads the Kotlin text", expected.id,
                oracle.id(rewritten).id)
        }
    }

    private data class History(
        val label: String,
        val history: List<Pair<String, String>>,
        val statePos: Int,
    )

    private fun historySave(params: String, desc: String, case: History): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Flood.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + case.history.size).toString()),
            SaveRecord("STATEPOS", case.statePos.toString()),
        )
        for ((key, value) in case.history) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
