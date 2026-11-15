package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: sixteen.
 *
 * Every `sixteen` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes
 * a Kotlin-written save for every preset and lets C read it.
 *
 * Two quirks get their own test. `encode_params` ignores its `full`
 * argument, so a target game keeps `m` in the short id and in both
 * `PARAMS` and `CPARAMS`. That is the opposite of flood and fifteen,
 * whose short form drops the non-geometric parameters.
 *
 * The history cases are built here, so the corpus stays frozen. One
 * case, undo or redo across a `SOLVE`, is compared in Kotlin only: the
 * C debug oracle asserts in `game_redraw` when a whole board is
 * replaced (`work/specs/sixteen.md`, section 10, risk 2). That is an
 * upstream animation bug and test tooling, not a game deviation.
 */
class SixteenSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every sixteen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == Sixteen.name
                }
                .sorted()
                .toList()
            assertTrue("no sixteen saves in the corpus", files.isNotEmpty())

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Sixteen, SgtpSave.parse(text))

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
        val paramsList = Sixteen.presets().map { it.params }.distinct()
        assertEquals("one save per preset", 5, paramsList.size)

        for (params in paramsList) {
            val midend = Midend.newGame(Sixteen, params, SEED)
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
    fun `the target stays in the short id and in both parameter records`() =
        OracleClient().use { oracle ->
            // `encode_params` ignores `full` for this game, so the short
            // form and the long form are the same string. Flood and
            // fifteen lose their non-geometric parameters in `PARAMS`
            // and in the game id; sixteen must not.
            val params = Sixteen.decodeParams("4x4m10")
            assertEquals(
                "the short form keeps the target",
                Sixteen.encodeParams(params, true),
                Sixteen.encodeParams(params, false),
            )

            val midend = Midend.newGame(Sixteen, "4x4m10", SEED)
            val save = midend.serialise()
            assertEquals("PARAMS", "4x4m10", save.value("PARAMS"))
            assertEquals("CPARAMS", "4x4m10", save.value("CPARAMS"))
            assertTrue("the id keeps the target", midend.id().startsWith("4x4m10:"))
            assertEquals(
                "the id decodes back to the target",
                10,
                (Sixteen.decodeParams(midend.id().substringBefore(':')) as Sixteen.Params)
                    .moveTarget,
            )

            val text = save.serialise()
            val expected = oracle.newGame("sixteen", "4x4m10", SEED)
            assertEquals("the oracle generated the same save", expected.state, text)
            assertEquals("the oracle generated the same id", expected.id, midend.id())
            assertEquals("the oracle reads the Kotlin save", expected.id, oracle.id(text).id)
            assertEquals("status text", traceStatus(oracle, text), midend.statusText())
            assertEquals("Moves: 0 (target 10)", midend.statusText())
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        // The corpus has initial saves only. Build the history cases
        // here: a played move, an undone position with a live redo
        // branch, a solve, and a restart.
        val params = "4x4"
        val desc = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val base = historySave(params, desc, emptyList(), 1)

        val cases = listOf(
            // A click on the right border of row 0 produces `R0,1`.
            History("one move", listOf(SaveRecord("MOVE", "R0,1")), 2) {
                oracle.key(base, 264, 72, Buttons.LEFT_BUTTON).state
            },
            History(
                "undone position with a redo branch",
                listOf(SaveRecord("MOVE", "R0,1"), SaveRecord("MOVE", "C0,1")),
                2,
            ) { text -> oracle.redo(text).state },
            History("solve", listOf(SaveRecord("SOLVE", "S")), 2) {
                oracle.solve(base).state
            },
            // There is no restart operation in the oracle. Undo from a
            // RESTART entry is safe, because the move type suppresses
            // the animation; a redo into one would replace the whole
            // board and hits the same upstream assertion as a solve.
            History(
                "restart",
                listOf(SaveRecord("MOVE", "R0,1"), SaveRecord("RESTART", desc)),
                3,
            ) { text -> oracle.undo(text).state },
        )

        for (case in cases) {
            val text = historySave(params, desc, case.history, case.statePos)
            val midend = Midend.fromSave(Sixteen, SgtpSave.parse(text))

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

            // Each played case must show a real effect: one more move
            // and a live history.
            when (case.label) {
                "one move", "undone position with a redo branch" -> {
                    assertEquals(1, (midend.state as Sixteen.State).moveCount)
                    assertEquals("Moves: 1", midend.statusText())
                    assertTrue("${case.label} adds history", midend.canUndo)
                    assertEquals(
                        "${case.label} history size",
                        1 + case.history.size,
                        midend.stateCount,
                    )
                    assertEquals(
                        "${case.label} MOVE record",
                        listOf("R0,1"),
                        midend.serialise().values("MOVE").take(1),
                    )
                }

                "solve" -> {
                    assertTrue((midend.state as Sixteen.State).usedSolve)
                    assertEquals("Moves since auto-solve: 0", midend.statusText())
                    assertEquals(listOf("S"), midend.serialise().values("SOLVE"))
                }

                "restart" -> {
                    assertEquals(0, (midend.state as Sixteen.State).moveCount)
                    assertEquals("Moves: 0", midend.statusText())
                    assertEquals(desc, midend.serialise().value("RESTART"))
                    assertTrue("${case.label} adds history", midend.canUndo)
                }
            }

            // C produces the same history state, and Kotlin reads it.
            val cText = case.fromC(text)
            val cState = oracle.id(cText)
            assertEquals("${case.label} C writes canonical text", cText, cState.state)
            val reloaded = Midend.fromSave(Sixteen, SgtpSave.parse(cText))
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

        // Undo and redo across a SOLVE are Kotlin-only. The C debug
        // oracle aborts in game_redraw here, so this test never calls
        // the C undo or redo on a solved save. SixteenGameTest pins the
        // same behavior; it is repeated at the save level because this
        // is the file that documents the exclusion.
        val solvedText = historySave(
            params,
            desc,
            listOf(SaveRecord("SOLVE", "S")),
            2,
        )
        val solved = Midend.fromSave(Sixteen, SgtpSave.parse(solvedText))
        assertTrue("undo from a solved state", solved.undo())
        assertEquals("Moves: 0", solved.statusText())
        assertFalse((solved.state as Sixteen.State).usedSolve)
        assertTrue("a redo branch is live", solved.canRedo)
        assertTrue("redo into the solved state", solved.redo())
        assertTrue((solved.state as Sixteen.State).usedSolve)
        assertEquals("Moves since auto-solve: 0", solved.statusText())
        assertFalse("the redo branch is gone", solved.canRedo)
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
            SaveRecord("GAME", Sixteen.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
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
