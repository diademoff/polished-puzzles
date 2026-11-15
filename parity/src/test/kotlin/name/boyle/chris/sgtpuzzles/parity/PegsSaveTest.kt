package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: pegs.
 *
 * Every `pegs` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. A Kotlin save for every
 * readable preset is loaded by C with the same id and canonical text.
 *
 * `5x5random` is the one save that upstream cannot reload. The full
 * encoding of its short parameters is `5x5cross`, and the loader
 * validates `PARAMS` in full mode, where Cross has no 5x5 board. The
 * test pins the exact save text and both error messages. The corpus
 * stays frozen and does not contain the file.
 *
 * The `full` asymmetry also shows on Octagon: `PARAMS` is the short
 * form decoded over the defaults, so it says `7x7cross`, while
 * `CPARAMS` keeps `7x7octagon`. Both are upstream behavior.
 *
 * The history cases are built in memory: a drag move, an undone
 * position with a live redo branch, a completed board, and a restart.
 */
class PegsSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every pegs save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { file ->
                    SgtpSave.parse(file.readText()).value("GAME") == Pegs.name
                }
                .sorted()
                .toList()
            assertEquals("nine readable preset saves", 9, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Pegs, SgtpSave.parse(text))

                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals("", midend.statusText())
            }
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            val paramsList = Pegs.presets().map { it.params }.distinct()
            assertEquals("one save per preset", 9, paramsList.size)

            for (params in paramsList) {
                val midend = Midend.newGame(Pegs, params, SEED)
                val text = midend.serialise().serialise()
                if (params == "5x5random") {
                    // Upstream rejects the long-term form of its own
                    // save. The next test pins the exact text and errors.
                    assertEquals("5x5cross", midend.serialise().value("PARAMS"))
                    assertEquals("5x5random", midend.serialise().value("CPARAMS"))
                    continue
                }

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
    fun `the 5x5random save writes Cross params that upstream rejects`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Pegs, "5x5random", SEED)
            val save = midend.serialise()

            // The exact file. PARAMS is the full encoding of the short
            // `5x5` form, so the board type is gone.
            val expected = """
                SAVEFILE:41:Simon Tatham's Portable Puzzle Collection
                VERSION :1:1
                GAME    :4:Pegs
                PARAMS  :8:5x5cross
                CPARAMS :9:5x5random
                SEED    :5:12345
                DESC    :25:OPPOOPPPPOPPHPPOPPPPOOPPO
                NSTATES :1:1
                STATEPOS:1:1
            """.trimIndent() + "\n"
            assertEquals("the exact 5x5random save", expected, save.serialise())
            assertEquals(
                "the oracle writes the same save",
                expected,
                oracle.newGame("pegs", "5x5random", SEED).state,
            )
            assertEquals("5x5cross", save.value("PARAMS"))
            assertEquals("5x5random", save.value("CPARAMS"))
            assertTrue("the id drops the type", midend.id().startsWith("5x5:"))

            // Upstream cannot reload its own save: PARAMS is validated
            // in full mode and `5x5cross` is not a Cross board.
            try {
                oracle.id(expected)
                throw AssertionError("the oracle reloaded a 5x5random save")
            } catch (e: OracleException) {
                assertEquals(
                    "Long-term parameters in save file are invalid",
                    e.message,
                )
            }
            try {
                Midend.fromSave(Pegs, save)
                throw AssertionError("Kotlin reloaded a 5x5random save")
            } catch (e: IllegalStateException) {
                assertEquals(
                    "long-term parameters: " +
                        "This board type is only supported at 5x7, 5x9, 7x7, 7x9, and 9x9",
                    e.message,
                )
            }
        }

    @Test
    fun `a Cross save and an Octagon save keep their long-term params`() =
        OracleClient().use { oracle ->
            val cross = Midend.newGame(Pegs, "5x7cross", SEED)
            val crossSave = cross.serialise()
            assertEquals("5x7cross", crossSave.value("PARAMS"))
            assertEquals("5x7cross", crossSave.value("CPARAMS"))
            assertEquals(
                "the Cross corpus save",
                File(testdata, "saves/initial/pegs/5x7cross.sgtp").readText(),
                crossSave.serialise(),
            )
            assertEquals(
                "the oracle Cross save",
                crossSave.serialise(),
                oracle.newGame("pegs", "5x7cross", SEED).state,
            )

            val octagon = Midend.newGame(Pegs, "7x7octagon", SEED)
            val octagonSave = octagon.serialise()
            // The short id cannot carry the type, so PARAMS decodes to
            // Cross on default dimensions. CPARAMS keeps Octagon.
            assertEquals("7x7cross", octagonSave.value("PARAMS"))
            assertEquals("7x7octagon", octagonSave.value("CPARAMS"))
            assertEquals(
                "the Octagon corpus save",
                File(testdata, "saves/initial/pegs/7x7octagon.sgtp").readText(),
                octagonSave.serialise(),
            )
            assertEquals(
                "the oracle Octagon save",
                octagonSave.serialise(),
                oracle.newGame("pegs", "7x7octagon", SEED).state,
            )

            val loaded = oracle.id(octagonSave.serialise())
            assertEquals(octagon.id(), loaded.id)
            assertEquals(octagon.status(), loaded.status)
        }

    @Test
    fun `history records round-trip in both directions`() =
        OracleClient().use { oracle ->
            val desc = INITIAL_DESC
            val move = SaveRecord("MOVE", "2,1-2,3")
            val second = SaveRecord("MOVE", "2,4-2,2")
            val twoMoves = listOf(move, second)
            val initialText = historySave(desc, emptyList(), 1)
            val completedDesc = "PPH" + "O".repeat(32)
            val completedInitial = historySave(completedDesc, emptyList(), 1)

            val cases = listOf(
                History(
                    label = "one move",
                    desc = desc,
                    records = listOf(move),
                    statePos = 2,
                    status = 0,
                    canUndo = true,
                    canRedo = false,
                    fromC = { client, _ ->
                        client.gestureSized(
                            initialText,
                            98,
                            65,
                            98,
                            131,
                            4,
                            197,
                            263,
                        ).state
                    },
                ),
                History(
                    label = "undone position with a redo branch",
                    desc = desc,
                    records = twoMoves,
                    statePos = 2,
                    status = 0,
                    canUndo = true,
                    canRedo = true,
                    fromC = { client, _ ->
                        client.undo(historySave(desc, twoMoves, 3)).state
                    },
                ),
                History(
                    label = "completed state",
                    desc = completedDesc,
                    records = listOf(SaveRecord("MOVE", "0,0-2,0")),
                    statePos = 2,
                    status = 1,
                    canUndo = true,
                    canRedo = false,
                    fromC = { client, _ ->
                        client.gestureSized(
                            completedInitial,
                            32,
                            32,
                            98,
                            32,
                            4,
                            197,
                            263,
                        ).state
                    },
                ),
                History(
                    label = "restart",
                    desc = desc,
                    records = listOf(move, SaveRecord("RESTART", desc)),
                    statePos = 3,
                    status = 0,
                    canUndo = true,
                    canRedo = false,
                    fromC = { client, text -> client.undo(text).state },
                ),
            )

            val baseText = Midend.fromSave(
                Pegs,
                SgtpSave.parse(historySave(desc, emptyList(), 1)),
            ).textFormat()

            for (case in cases) {
                val text = historySave(case.desc, case.records, case.statePos)
                val midend = Midend.fromSave(Pegs, SgtpSave.parse(text))

                assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
                assertEquals(
                    "${case.label} state count",
                    case.records.size + 1,
                    midend.stateCount,
                )
                if (case.label == "completed state") {
                    assertEquals(1, midend.status())
                } else {
                    assertEquals(0, midend.status())
                }
                assertEquals(case.status, midend.status())
                assertEquals(case.canUndo, midend.canUndo)
                assertEquals(case.canRedo, midend.canRedo)
                assertEquals("", midend.statusText())
                if (case.label == "one move" || case.label == "completed state") {
                    assertTrue(
                        "${case.label} changes the board",
                        midend.textFormat() != baseText,
                    )
                }

                val expected = oracle.id(text)
                assertEquals("${case.label} id", expected.id, midend.id())
                assertEquals("${case.label} status", expected.status, midend.status())
                assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
                assertFalse(midend.serialise().records.any {
                    it.key == "AUXINFO" || it.key == "UI"
                })

                // The other direction: a C-produced save loads in Kotlin
                // and rewrites to the same canonical text.
                val cText = case.fromC(oracle, text)
                val cState = oracle.id(cText)
                assertEquals("${case.label} C canonical text", cText, cState.state)
                val reloaded = Midend.fromSave(Pegs, SgtpSave.parse(cText))
                assertEquals(
                    "${case.label} reloaded canonical text",
                    cText,
                    reloaded.serialise().serialise(),
                )
                assertEquals("${case.label} reloaded id", cState.id, reloaded.id())
                assertEquals("${case.label} reloaded status", cState.status, reloaded.status())
                assertEquals("${case.label} reloaded can undo", cState.canUndo, reloaded.canUndo)
                assertEquals("${case.label} reloaded can redo", cState.canRedo, reloaded.canRedo)
                assertEquals(
                    "${case.label} reloaded text",
                    oracle.text(cText),
                    reloaded.textFormat(),
                )
            }
        }

    @Test
    fun `a live Kotlin drag and restart write the same history`() {
        val desc = INITIAL_DESC
        val move = SaveRecord("MOVE", "2,1-2,3")
        val second = SaveRecord("MOVE", "2,4-2,2")
        val twoMoves = listOf(move, second)
        val base = Midend.fromSave(
            Pegs,
            SgtpSave.parse(historySave(desc, emptyList(), 1)),
        )
        val baseText = base.textFormat()

        drag(base, 98, 65, 98, 131)
        assertEquals(2, base.stateCount)
        assertTrue("the drag changes the board", base.textFormat() != baseText)
        assertEquals(
            "the first move record",
            historySave(desc, listOf(move), 2),
            base.serialise().serialise(),
        )

        val two = Midend.fromSave(
            Pegs,
            SgtpSave.parse(historySave(desc, listOf(move), 2)),
        )
        drag(two, 98, 164, 98, 98)
        assertEquals(3, two.stateCount)
        assertEquals(
            "the two move records",
            historySave(desc, twoMoves, 3),
            two.serialise().serialise(),
        )

        assertTrue("undo leaves a redo branch", two.undo())
        assertTrue(two.canRedo)
        assertEquals(
            "the undone save keeps both move records",
            historySave(desc, twoMoves, 2),
            two.serialise().serialise(),
        )
        assertTrue("redo returns to the last state", two.redo())
        assertEquals(
            "the redone save",
            historySave(desc, twoMoves, 3),
            two.serialise().serialise(),
        )

        val restarted = Midend.fromSave(
            Pegs,
            SgtpSave.parse(historySave(desc, listOf(move), 2)),
        )
        assertTrue("restart records a state", restarted.restart())
        assertEquals(
            "the restart record is the description",
            historySave(
                desc,
                listOf(move, SaveRecord("RESTART", desc)),
                3,
            ),
            restarted.serialise().serialise(),
        )

        val completedDesc = "PPH" + "O".repeat(32)
        val completed = Midend.fromGameId(Pegs, "5x7:$completedDesc")
        drag(completed, 32, 32, 98, 32)
        assertEquals(1, completed.status())
        assertEquals(
            "the completing move",
            historySave(
                completedDesc,
                listOf(SaveRecord("MOVE", "0,0-2,0")),
                2,
            ),
            completed.serialise().serialise(),
        )
    }

    private data class History(
        val label: String,
        val desc: String,
        val records: List<SaveRecord>,
        val statePos: Int,
        val status: Int,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val fromC: (OracleClient, String) -> String,
    )

    private fun historySave(
        desc: String,
        records: List<SaveRecord>,
        statePos: Int,
    ): String {
        val saveRecords = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Pegs.name),
            SaveRecord("PARAMS", "5x7cross"),
            SaveRecord("CPARAMS", "5x7cross"),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + records.size).toString()),
            SaveRecord("STATEPOS", statePos.toString()),
        )
        saveRecords += records
        return SgtpSave.of(saveRecords).serialise()
    }

    private fun drag(midend: Midend, x0: Int, y0: Int, x1: Int, y1: Int) {
        val tile = Pegs.preferredTileSize
        assertEquals(
            "the press must start a drag",
            KeyOutcome.SomeEffect,
            midend.processKey(x0, y0, Buttons.LEFT_BUTTON, tile),
        )
        for (i in 1..STEPS) {
            assertEquals(
                "the drag is a UI update",
                KeyOutcome.SomeEffect,
                midend.processKey(
                    x0 + (x1 - x0) * i / STEPS,
                    y0 + (y1 - y0) * i / STEPS,
                    Buttons.LEFT_DRAG,
                    tile,
                ),
            )
        }
        assertEquals(
            "the release makes the move",
            KeyOutcome.SomeEffect,
            midend.processKey(x1, y1, Buttons.LEFT_RELEASE, tile),
        )
    }

    private companion object {
        const val SEED = "12345"
        const val STEPS = 4
        const val INITIAL_DESC = "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO"
    }
}
