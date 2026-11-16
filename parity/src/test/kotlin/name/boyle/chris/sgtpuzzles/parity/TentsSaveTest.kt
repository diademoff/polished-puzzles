package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: tents.
 *
 * Every corpus save is read by the Kotlin midend, rewritten byte for
 * byte, and reloaded by C. A Kotlin save for every preset is loaded by
 * C with the same id and text. The history cases cover a played click,
 * a drag, an undone position with a live redo branch, a `SOLVE`, and a
 * `RESTART`. The `PARAMS`/`CPARAMS` split and the absent `UI` record are
 * pinned.
 */
class TentsSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32

        const val GOLDEN_DESC =
            "ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"
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
    fun `every tents save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/tents")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(7, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Tents, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} keeps AUXINFO",
                    SgtpSave.parse(text).value("AUXINFO"),
                    midend.serialise().value("AUXINFO"),
                )
                assertFalse(
                    "${file.name} has no UI record",
                    midend.serialise().records.any { it.key == "UI" },
                )
            }

            // `default.sgtp` and `8x8de.sgtp` are the same file.
            assertEquals(
                File(testdata, "saves/initial/tents/8x8de.sgtp").readText(),
                File(testdata, "saves/initial/tents/default.sgtp").readText(),
            )
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Tents.presets().map { it.params }) {
                val midend = Midend.newGame(Tents, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertEquals("$params status", midend.status(), loaded.status)
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertNull("$params has no AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
            }
        }

    @Test
    fun `a played click, a drag and a live redo branch round-trip`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Tents, "8x8de", SEED)
            midend.processKey(32, 32, Buttons.LEFT_BUTTON, TILE)
            midend.processKey(32, 32, Buttons.LEFT_RELEASE, TILE)
            val clicked = midend.serialise().serialise()
            assertEquals(listOf("T0,0"), midend.serialise().values("MOVE"))
            assertSame("click", oracle.id(clicked), midend)

            for (point in listOf(
                OracleClient.Point(64, 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(96, 32, Buttons.LEFT_DRAG),
                OracleClient.Point(96, 32, Buttons.LEFT_RELEASE),
            )) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            val dragged = midend.serialise().serialise()
            assertEquals(listOf("T0,0", "N2,0"), midend.serialise().values("MOVE"))
            assertSame("drag", oracle.id(dragged), midend)

            assertTrue(midend.undo())
            val undone = midend.serialise().serialise()
            assertEquals("2", SgtpSave.parse(undone).value("STATEPOS"))
            assertEquals("3", SgtpSave.parse(undone).value("NSTATES"))
            assertTrue(midend.canRedo)
            assertSame("undone redo branch", oracle.id(undone), midend)

            val reloaded = Midend.fromSave(Tents, SgtpSave.parse(undone))
            assertTrue(reloaded.canRedo)
            assertTrue(reloaded.canUndo)
            assertEquals(3, reloaded.stateCount)
            assertEquals(2, reloaded.statePos)

            val redone = oracle.redo(undone)
            assertTrue(midend.redo())
            assertSame("redone", redone, midend)
        }

    @Test
    fun `a SOLVE and a RESTART round-trip in both directions`() =
        OracleClient().use { oracle ->
            val solved = Midend.newGame(Tents, "8x8de", SEED)
            assertTrue(solved.solve())
            val solveText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(solveText).values("SOLVE").size)
            assertSame("solve save", oracle.id(solveText), solved)

            val loaded = Midend.fromSave(Tents, SgtpSave.parse(solveText))
            assertEquals(solveText, loaded.serialise().serialise())
            assertEquals(1, loaded.status())
            assertFalse(loaded.takeCompletedEvent())

            val restarted = Midend.newGame(Tents, "8x8de", SEED)
            restarted.processKey(32, 32, Buttons.LEFT_BUTTON, TILE)
            restarted.processKey(32, 32, Buttons.LEFT_RELEASE, TILE)
            assertTrue(restarted.restart())
            val restartText = restarted.serialise().serialise()
            assertEquals(1, SgtpSave.parse(restartText).values("RESTART").size)
            assertSame("restart save", oracle.id(restartText), restarted)

            val restartLoaded = Midend.fromSave(Tents, SgtpSave.parse(restartText))
            assertEquals(restartText, restartLoaded.serialise().serialise())
            assertTrue(restartLoaded.canUndo)
            assertFalse(restartLoaded.canRedo)
        }

    @Test
    fun `the PARAMS and CPARAMS split is pinned and there is no UI record`() =
        OracleClient().use { oracle ->
            for ((params, longTerm) in listOf(
                "8x8dt" to "8x8de",
                "10x10dt" to "10x10de",
                "15x15dt" to "15x15de",
            )) {
                val text = File(testdata, "saves/initial/tents/$params.sgtp").readText()
                val save = SgtpSave.parse(text)
                assertEquals("$params PARAMS", longTerm, save.value("PARAMS"))
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                assertNull("$params no UI", save.value("UI"))

                val midend = Midend.fromSave(Tents, save)
                assertEquals(text, midend.serialise().serialise())
                assertSame("$params", oracle.id(text), midend)
            }
        }

    @Test
    fun `a tree move and the overshooting description are defined errors`() =
        OracleClient().use { oracle ->
            val treeMove = saveText(
                "8x8de",
                GOLDEN_DESC,
                listOf("MOVE" to "T1,0"),
            )
            val oracleError = try {
                oracle.id(treeMove)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertTrue(
                "C rejects a tree move",
                oracleError != null && oracleError.contains("invalid move"),
            )
            val kotlinError = try {
                Midend.fromSave(Tents, SgtpSave.parse(treeMove))
                null
            } catch (e: RuntimeException) {
                e.message
            }
            assertTrue(
                "Kotlin rejects a tree move",
                kotlinError != null && kotlinError.contains("invalid move"),
            )

            // D-009: C aborts here, so the oracle is not asked. The
            // port throws a message that names the cause.
            val zeros = ",0".repeat(16)
            for ((desc, message) in listOf(
                "ddddddddz$zeros" to "tents: the description runs past the grid",
                "dddddddcz_$zeros" to "tents: the description ends on a non-tree cell",
            )) {
                val save = saveText("8x8de", desc)
                assertEquals(null, Tents.validateDesc(Tents.decodeParams("8x8de"), desc))
                val error = try {
                    Midend.fromSave(Tents, SgtpSave.parse(save))
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                assertEquals(message, error)
            }
        }

    private fun saveText(
        params: String,
        desc: String,
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Tents.name
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }
}
