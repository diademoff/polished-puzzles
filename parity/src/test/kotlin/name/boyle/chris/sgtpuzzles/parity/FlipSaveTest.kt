package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: flip.
 *
 * Every `flip` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes
 * a Kotlin-written save for every preset, including the seeded Random
 * games, and lets C read it.
 *
 * One quirk gets its own test. `encode_params` writes the shape suffix
 * only in the full form, so the game id of a Random game is `3x3:...`
 * with no `r`. The midend then filters the long-term parameters through
 * the short form, so a seeded Random save holds `PARAMS :4:3x3c` and
 * `CPARAMS :4:3x3r`. This is upstream behavior and must not be "fixed".
 *
 * The history cases are built here, so the corpus stays frozen. A
 * `SOLVE` record for flip is a hint mask: the board stays open and the
 * status text becomes `Auto-solver used.`. The cheated completion then
 * clicks the hinted cells and ends with `Auto-solved.`.
 */
class FlipSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every flip save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == Flip.name
                }
                .sorted()
                .toList()
            assertTrue("no flip saves in the corpus", files.isNotEmpty())

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Flip, SgtpSave.parse(text))

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
    fun `a Kotlin save for every preset loads in the oracle`() = OracleClient().use { oracle ->
        val paramsList = Flip.presets().map { it.params }.distinct()
        assertEquals("one save per preset", 6, paramsList.size)

        for (params in paramsList) {
            val midend = Midend.newGame(Flip, params, SEED)
            val text = midend.serialise().serialise()
            val loaded = oracle.id(text)

            assertEquals("$params id", midend.id(), loaded.id)
            assertEquals("$params canonical text", text, loaded.state)
            assertEquals("$params text", midend.textFormat(), oracle.text(text))
            assertEquals("$params status", midend.status(), loaded.status)
            assertFalse("$params starts with no undo", loaded.canUndo)
            assertFalse("$params starts with no redo", loaded.canRedo)
        }
    }

    @Test
    fun `a seeded Random save keeps Crosses in PARAMS and Random in CPARAMS`() =
        OracleClient().use { oracle ->
            // The id is the short form, so it drops the shape. `PARAMS`
            // is the short form decoded over the defaults, which is
            // Crosses; only `CPARAMS` keeps the Random the user chose.
            val midend = Midend.newGame(Flip, "3x3r", SEED)
            val save = midend.serialise()

            assertEquals("PARAMS", "3x3c", save.value("PARAMS"))
            assertEquals("CPARAMS", "3x3r", save.value("CPARAMS"))
            assertEquals("3x3:${midend.desc}", midend.id())
            assertFalse("the id drops the shape", midend.id().startsWith("3x3r"))

            // The corpus file is the byte-for-byte reference. Do not
            // regenerate it.
            val corpus = File(testdata, "saves/initial/flip/3x3r.sgtp").readText()
            assertEquals("the corpus save", corpus, save.serialise())

            val expected = oracle.newGame("flip", "3x3r", SEED)
            assertEquals("the oracle generated the same save", expected.state, save.serialise())
            assertEquals("the oracle generated the same id", expected.id, midend.id())
            assertEquals(
                "the oracle reads the Kotlin save",
                expected.id,
                oracle.id(save.serialise()).id,
            )

            // The same shape holds for the other Random presets.
            for (params in listOf("4x4r", "5x5r")) {
                val random = Midend.newGame(Flip, params, SEED)
                assertEquals(
                    "$params PARAMS",
                    params.replace('r', 'c'),
                    random.serialise().value("PARAMS"),
                )
                assertEquals("$params CPARAMS", params, random.serialise().value("CPARAMS"))
                assertEquals(
                    "$params corpus save",
                    File(testdata, "saves/initial/flip/$params.sgtp").readText(),
                    random.serialise().serialise(),
                )
            }
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        // The corpus has initial saves only. Build the history cases
        // here: a played move, an undone position with a live redo
        // branch, a hint/solve state, a cheated completion, and a
        // restart.
        val params = "3x3c"
        val desc = "d074191345d1644c17058,c90"
        val base = historySave(params, desc, emptyList(), 1)
        val baseText = Midend.fromSave(Flip, SgtpSave.parse(base)).textFormat()

        // The hinted cells of the seed 12345 board, in click order.
        val solutionCells = listOf(0 to 1, 1 to 1, 1 to 2, 2 to 2)
        val solutionPoints = solutionCells.map { (x, y) ->
            OracleClient.Point(x * 48 + 24, y * 48 + 24, Buttons.LEFT_BUTTON)
        }

        val cases = listOf(
            History("one move", listOf(SaveRecord("MOVE", "M0,0")), 2) { text ->
                oracle.key(text, 24, 24, Buttons.LEFT_BUTTON).state
            },
            History(
                "undone position with a redo branch",
                listOf(SaveRecord("MOVE", "M0,0"), SaveRecord("MOVE", "M1,1")),
                2,
            ) { text -> oracle.undo(text).state },
            History("solve", listOf(SaveRecord("SOLVE", "S000110011")), 2) {
                oracle.solve(base).state
            },
            History(
                "cheated completion",
                listOf(SaveRecord("SOLVE", "S000110011")) +
                    solutionCells.map { (x, y) -> SaveRecord("MOVE", "M$x,$y") },
                6,
            ) { _ -> oracle.pointsSized(oracle.solve(base).state, solutionPoints).state.state },
            // There is no restart operation in the oracle. Undo from a
            // RESTART entry is safe: flip's redraw has no animation and
            // a non-MOVE record is not drawn at all.
            History(
                "restart",
                listOf(SaveRecord("MOVE", "M0,0"), SaveRecord("RESTART", desc)),
                3,
            ) { text -> oracle.undo(text).state },
        )

        for (case in cases) {
            val text = historySave(params, desc, case.history, case.statePos)
            val midend = Midend.fromSave(Flip, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())

            val expected = oracle.id(text)
            assertEquals("${case.label} C text", text, expected.state)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
            assertEquals(
                "${case.label} status text",
                traceStatus(oracle, text),
                midend.statusText(),
            )

            // Each played case must show a real effect: a changed board
            // and a move count that matches the clicks.
            val state = midend.state as Flip.State
            when (case.label) {
                "one move", "undone position with a redo branch" -> {
                    assertEquals(1, state.moves)
                    assertEquals("Moves: 1", midend.statusText())
                    assertTrue(
                        "${case.label} changes the board",
                        baseText != midend.textFormat(),
                    )
                    assertTrue("${case.label} adds history", midend.canUndo)
                    assertEquals(
                        "${case.label} MOVE record",
                        "M0,0",
                        midend.serialise().value("MOVE"),
                    )
                }

                "solve" -> {
                    assertTrue("the solve sets used_solve", state.cheated)
                    assertTrue("the solve sets the hints", state.hintsActive)
                    assertEquals("the solve does not complete", 0, midend.status())
                    assertEquals("the solve does not move the lights", baseText, midend.textFormat())
                    assertEquals("Auto-solver used. Moves: 0", midend.statusText())
                    assertEquals(listOf("S000110011"), midend.serialise().values("SOLVE"))
                }

                "cheated completion" -> {
                    assertTrue("the board is complete", state.completed)
                    assertTrue("the completion is cheated", state.cheated)
                    assertEquals(4, state.moves)
                    assertEquals(1, midend.status())
                    assertEquals("Auto-solved. Moves: 4", midend.statusText())
                    assertTrue(
                        "the completion changes the board",
                        midend.textFormat() != baseText,
                    )
                }

                "restart" -> {
                    assertEquals(0, state.moves)
                    assertFalse("the restart clears completion", state.completed)
                    assertEquals("the restart restores the board", baseText, midend.textFormat())
                    assertEquals("Moves: 0", midend.statusText())
                    assertEquals(desc, midend.serialise().value("RESTART"))
                    assertTrue("${case.label} adds history", midend.canUndo)
                    assertFalse("${case.label} is the last state", midend.canRedo)
                }
            }

            // C produces the same history state, and Kotlin reads it.
            val cText = case.fromC(text)
            val cState = oracle.id(cText)
            assertEquals("${case.label} C writes canonical text", cText, cState.state)
            val reloaded = Midend.fromSave(Flip, SgtpSave.parse(cText))
            assertEquals(
                "${case.label} reloaded canonical text",
                cText,
                reloaded.serialise().serialise(),
            )
            assertEquals("${case.label} reloaded id", cState.id, reloaded.id())
            assertEquals("${case.label} reloaded status", cState.status, reloaded.status())
            assertEquals("${case.label} reloaded can undo", cState.canUndo, reloaded.canUndo)
            assertEquals("${case.label} reloaded can redo", cState.canRedo, reloaded.canRedo)
            assertEquals("${case.label} reloaded text", oracle.text(cText), reloaded.textFormat())
        }
    }

    private data class History(
        val label: String,
        val history: List<SaveRecord>,
        val statePos: Int,
        val fromC: (String) -> String,
    )

    private fun historySave(
        params: String,
        desc: String,
        history: List<SaveRecord>,
        statePos: Int,
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Flip.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + history.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        records += history
        return SgtpSave.of(records).serialise()
    }

    private fun traceStatus(oracle: OracleClient, save: String): String? {
        val ops = (oracle.trace(save).field("ops") as JsonValue.Arr).items
        var status: String? = null
        for (item in ops) {
            val op = item as JsonValue.Obj
            if ((op.fields["op"] as? JsonValue.Str)?.value == "status") {
                status = op.string("text")
            }
        }
        return status
    }

    private companion object {
        const val SEED = "12345"
    }
}
