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
 * The history cases are built here, so the corpus stays frozen. A
 * `SOLVE` record is undone and redone in both implementations: the
 * midend clears the animation for every record type that is not `MOVE`,
 * so the C debug oracle does not reach its `abs(dx) == TILE_SIZE`
 * assertion. A `MOVE` record whose move text is `S` does reach it, so
 * that one shape is compared in Kotlin only and pinned here
 * (`work/specs/sixteen.md`, section 10, risk 2). That is an upstream
 * animation bug in the game's redraw, not a game deviation.
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
            // There is no restart operation in the oracle. Undo from
            // a RESTART entry is safe, and so is a redo into one: the
            // midend clears the animation for every record type that is
            // not MOVE, so the whole-board replacement is not drawn.
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

        // Undo and redo across a SOLVE are safe in C. The midend clears
        // the animation for every record type that is not `MOVE`, and a
        // `SOLVE` also has no `SOLVE_ANIMATES` flag for this game, so
        // `game_redraw` never reaches the `abs(dx) == TILE_SIZE`
        // assertion. The shape that does abort the debug oracle is a
        // `MOVE` record whose move text is `S`; it has its own test
        // below.
        val solvedText = oracle.solve(base).state
        assertEquals("SOLVE", SgtpSave.parse(solvedText).records.last().key)
        var solvedExpected = oracle.id(solvedText)
        val solved = Midend.fromSave(Sixteen, SgtpSave.parse(solvedText))
        assertEquals("the solved save", solvedExpected.state, solved.serialise().serialise())
        assertEquals(solvedExpected.id, solved.id())
        assertEquals(solvedExpected.status, solved.status())

        solvedExpected = oracle.undo(solvedText)
        assertTrue("undo from a solved state", solved.undo())
        assertEquals(
            "undo writes the C text",
            solvedExpected.state,
            solved.serialise().serialise(),
        )
        assertEquals("undo id", solvedExpected.id, solved.id())
        assertEquals("undo status", solvedExpected.status, solved.status())
        assertEquals("undo canUndo", solvedExpected.canUndo, solved.canUndo)
        assertEquals("undo canRedo", solvedExpected.canRedo, solved.canRedo)
        assertFalse("the undo leaves the solve", (solved.state as Sixteen.State).usedSolve)
        assertEquals("Moves: 0", solved.statusText())

        solvedExpected = oracle.redo(solvedExpected.state)
        assertTrue("redo into the solved state", solved.redo())
        assertEquals(
            "redo writes the C text",
            solvedExpected.state,
            solved.serialise().serialise(),
        )
        assertEquals("redo id", solvedExpected.id, solved.id())
        assertEquals("redo status", solvedExpected.status, solved.status())
        assertEquals("redo canUndo", solvedExpected.canUndo, solved.canUndo)
        assertEquals("redo canRedo", solvedExpected.canRedo, solved.canRedo)
        assertTrue("the redo enters the solve", (solved.state as Sixteen.State).usedSolve)
        assertEquals("Moves since auto-solve: 0", solved.statusText())
    }

    @Test
    fun `a MOVE record with the solve text is pinned without the oracle`() {
        // C executes the text `S` for a `MOVE` record exactly as for a
        // `SOLVE` record, so the board is the same. The record type is
        // not. The midend keeps the animation for `MOVE`, and
        // `game_redraw` then asserts on the whole-board replacement:
        //
        //   {"op":"undo","state":<NSTATES 2, STATEPOS 2, MOVE :1:S>}
        //   sixteen.c:1076: game_redraw: Assertion
        //   `abs(dx) == TILE_SIZE' failed.  exit 134
        //
        // The same save at `STATEPOS 1` aborts on `redo`. A release C
        // build may not abort, because `NDEBUG` removes the assertion,
        // but the debug oracle cannot run this comparison. So the save
        // shape is pinned here and the history is checked in Kotlin
        // only.
        val desc = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"
        val moveSolve = historySave("4x4", desc, listOf(SaveRecord("MOVE", "S")), 2)
        assertEquals(
            "the pinned save shape",
            "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
                "VERSION :1:1\n" +
                "GAME    :7:Sixteen\n" +
                "PARAMS  :3:4x4\n" +
                "CPARAMS :3:4x4\n" +
                "DESC    :38:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4\n" +
                "NSTATES :1:2\n" +
                "STATEPOS:1:2\n" +
                "MOVE    :1:S\n",
            moveSolve,
        )

        val midend = Midend.fromSave(Sixteen, SgtpSave.parse(moveSolve))
        assertTrue(
            "the solve text marks the state solved",
            (midend.state as Sixteen.State).usedSolve,
        )
        assertEquals("Moves since auto-solve: 0", midend.statusText())
        assertEquals("MOVE", midend.serialise().records.last().key)
        assertEquals("S", midend.serialise().value("MOVE"))

        assertTrue("undo from the move solve", midend.undo())
        assertFalse("the undo leaves the solve", (midend.state as Sixteen.State).usedSolve)
        assertEquals("Moves: 0", midend.statusText())
        assertTrue("a redo branch is live", midend.canRedo)

        assertTrue("redo into the move solve", midend.redo())
        assertTrue("the redo enters the solve", (midend.state as Sixteen.State).usedSolve)
        assertEquals("Moves since auto-solve: 0", midend.statusText())
        assertFalse("the redo branch is gone", midend.canRedo)
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
