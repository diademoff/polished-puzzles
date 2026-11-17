package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Towers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: towers.
 *
 * The eight frozen initial saves round-trip byte for byte in both
 * directions. Towers writes `AUXINFO`, no `UI`, no `TIME`, and no
 * `PRIVDESC`, and keeps the `PARAMS`/`CPARAMS` split: the long-term copy
 * is the bare width decoded over the defaults, so its difficulty is
 * Easy while `CPARAMS` keeps the request.
 */
class TowersSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 120_000)
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/towers").listFiles()
                ?.filter { it.isFile && it.extension == "sgtp" }
                ?.sorted()
                ?: emptyList()
            assertEquals("seven presets plus the default", 8, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Towers, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())

                val save = SgtpSave.parse(text)
                assertNotNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertTrue("${file.name} AUXINFO text", midend.auxInfo!!.startsWith("S"))
                assertNull("${file.name} PRIVDESC", save.value("PRIVDESC"))
                assertNull("${file.name} UI", save.value("UI"))
                assertNull("${file.name} TIME", save.value("TIME"))
                assertNull("${file.name} HEXSEED", save.value("HEXSEED"))
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }

            // The default save is byte-identical to the Easy save.
            assertEquals(
                File(testdata, "saves/initial/towers/5de.sgtp").readText(),
                File(testdata, "saves/initial/towers/default.sgtp").readText(),
            )

            // The split: PARAMS is the bare width decoded over the
            // defaults, so the difficulty is Easy, and CPARAMS keeps the
            // requested difficulty.
            for ((name, params) in listOf(
                "4de.sgtp" to "4de",
                "5de.sgtp" to "5de",
                "5dh.sgtp" to "5dh",
                "6de.sgtp" to "6de",
                "6dh.sgtp" to "6dh",
                "6dx.sgtp" to "6dx",
                "6du.sgtp" to "6du",
            )) {
                val save = SgtpSave.parse(File(testdata, "saves/initial/towers/$name").readText())
                assertEquals("$name PARAMS", params.substringBefore('d') + "de", save.value("PARAMS"))
                assertEquals("$name CPARAMS", params, save.value("CPARAMS"))
                assertTrue("$name AUXINFO", save.value("AUXINFO")!!.isNotEmpty())
            }
        }

    @Test(timeout = 600_000)
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Towers.presets().map { it.params } + "") {
                for (seed in seeds) {
                    val midend = Midend.newGame(Towers, params, seed)
                    val text = midend.serialise().serialise()
                    val loaded = oracle.id(text)
                    assertEquals("$params/$seed id", midend.id(), loaded.id)
                    assertEquals("$params/$seed canonical text", text, loaded.state)
                    assertEquals("$params/$seed status", midend.status(), loaded.status)
                    assertFalse("$params/$seed starts with no undo", loaded.canUndo)
                    assertFalse("$params/$seed starts with no redo", loaded.canRedo)
                    assertEquals(
                        "$params/$seed AUXINFO",
                        SgtpSave.parse(text).value("AUXINFO"),
                        SgtpSave.parse(loaded.state).value("AUXINFO"),
                    )
                    assertEquals(
                        "$params/$seed text",
                        oracle.text(text),
                        midend.textFormat(),
                    )
                }
            }
        }

    @Test(timeout = 300_000)
    fun `a played history round-trips in both directions`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("towers", "5de", "12345").state
            val save = SgtpSave.parse(start)
            val desc = save.value("DESC")!!
            val aux = requireNotNull(Midend.fromSave(Towers, save).auxInfo)

            val history = ArrayList<String>()
            history += start

            // A real digit move.
            val digit = oracle.keys(start, listOf(Buttons.CURSOR_UP, '3'.code)).state
            history += digit

            // A pencil move from a right click and a digit.
            val right = listOf(
                OracleClient.Point(78, 78, Buttons.RIGHT_BUTTON),
                OracleClient.Point(78, 78, Buttons.RIGHT_RELEASE),
                OracleClient.Point(0, 0, '2'.code),
            )
            history += oracle.pointsSized(start, right, 348, 348).state.state

            // An M move.
            history += oracle.keys(start, listOf('M'.code)).state

            // A D move.
            history += oracle.keys(start, listOf(Buttons.MOD_CTRL or Buttons.CURSOR_LEFT)).state

            // A solve.
            history += oracle.solve(start).state

            for (text in history) {
                val midend = Midend.fromSave(Towers, SgtpSave.parse(text))
                val loaded = oracle.id(text)
                assertEquals("round trip", text, midend.serialise().serialise())
                assertEquals("oracle", loaded.state, midend.serialise().serialise())
                assertEquals("status", loaded.status, midend.status())
                assertEquals(
                    "undo flag of ${SgtpSave.parse(text).values("MOVE").lastOrNull() ?: "start"}",
                    loaded.canUndo,
                    midend.canUndo,
                )
                assertEquals(
                    "redo flag of ${SgtpSave.parse(text).values("MOVE").lastOrNull() ?: "start"}",
                    loaded.canRedo,
                    midend.canRedo,
                )
            }

            // The in-memory shapes: a digit, a pencil mark, a D move, a
            // live redo branch, a solve, a restart, and an M move.
            val live = Midend.fromSave(Towers, save)
            live.processKey(0, 0, Buttons.CURSOR_UP, 48)
            live.processKey(0, 0, '3'.code, 48)
            assertEquals("R0,0,3", live.serialise().values("MOVE").last())
            assertEquals(2, live.stateCount)
            assertTrue(live.canUndo)
            assertFalse(live.canRedo)
            val movedText = live.serialise().serialise()
            assertEquals(digit, movedText)
            assertTrue(live.undo())
            assertTrue(live.canRedo)
            val undoneText = live.serialise().serialise()
            assertEquals(oracle.undo(digit).state, undoneText)
            assertEquals(2, SgtpSave.parse(undoneText).value("NSTATES")!!.toInt())
            assertTrue(live.redo())
            assertEquals(movedText, live.serialise().serialise())

            val pencil = Midend.fromSave(Towers, save)
            pencil.processKey(78, 78, Buttons.RIGHT_BUTTON, 48)
            pencil.processKey(78, 78, Buttons.RIGHT_RELEASE, 48)
            pencil.processKey(0, 0, '2'.code, 48)
            assertEquals("P0,0,2", pencil.serialise().values("MOVE").last())
            assertEquals(0, pencil.status())

            val marked = Midend.fromSave(Towers, save)
            marked.processKey(0, 0, 'M'.code, 48)
            assertEquals("M", marked.serialise().values("MOVE").last())
            assertEquals(0, marked.status())
            val markedText = marked.serialise().serialise()
            assertEquals(markedText, oracle.id(markedText).state)

            val clue = Midend.fromSave(Towers, save)
            clue.processKey(0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_LEFT, 48)
            assertEquals("D-1,0", clue.serialise().values("MOVE").last())
            val clueText = clue.serialise().serialise()
            assertEquals(clueText, oracle.id(clueText).state)
            assertTrue((clue.state as Towers.State).cluesDone[10])

            val solved = Midend.fromSave(Towers, save)
            assertTrue(solved.solve())
            assertEquals(1, solved.status())
            assertEquals(
                SgtpSave.parse(oracle.solve(start).state).serialise(),
                solved.serialise().serialise(),
            )
            assertEquals(aux, solved.serialise().value("SOLVE"))

            val restarted = Midend.fromSave(Towers, save)
            restarted.processKey(0, 0, Buttons.CURSOR_UP, 48)
            restarted.processKey(0, 0, '3'.code, 48)
            assertTrue(restarted.restart())
            assertEquals(3, restarted.stateCount)
            val restartText = restarted.serialise().serialise()
            assertEquals(
                "RESTART",
                restartText.lines().last { it.startsWith("RESTART") }.substringBefore(' '),
            )
            val loadedRestart = oracle.id(restartText)
            assertEquals(restarted.id(), loadedRestart.id)
            assertEquals(restartText, loadedRestart.state)
            assertEquals(desc, restarted.serialise().value("DESC"))
        }

    @Test(timeout = 240_000)
    fun `an aux-free save loads and solves from the clues`() =
        OracleClient().use { oracle ->
            val withAux = oracle.newGame("towers", "5dh", "12345").state
            val withoutAux = withAux.lineSequence()
                .filterNot { it.startsWith("AUXINFO") }
                .joinToString("\n", postfix = "\n")
            val expected = oracle.solve(withoutAux)
            val move = SgtpSave.parse(expected.state).value("SOLVE")!!
            assertNull(SgtpSave.parse(withoutAux).value("AUXINFO"))

            val midend = Midend.fromSave(Towers, SgtpSave.parse(withoutAux))
            assertNull(midend.auxInfo)
            assertEquals(move, Towers.solve(midend.state, null))
            assertTrue(midend.solve())
            assertEquals(expected.state, midend.serialise().serialise())
        }

    @Test(timeout = 120_000)
    fun `a hand-written answer round-trips and the solve uses it`() =
        OracleClient().use { oracle ->
            val first = Midend.newGame(Towers, "5de", "1").serialise()
            val second = Midend.newGame(Towers, "5de", "2").serialise()
            assertNotNull(first.value("AUXINFO"))
            assertNotNull(second.value("AUXINFO"))
            val records = first.records.map {
                if (it.key == "AUXINFO") {
                    SaveRecord("AUXINFO", second.value("AUXINFO")!!)
                } else {
                    it
                }
            }
            val midend = Midend.fromSave(Towers, SgtpSave.parse(SgtpSave.of(records).serialise()))
            assertEquals(second.value("AUXINFO"), midend.serialise().value("AUXINFO"))
            val aux = requireNotNull(midend.auxInfo)
            assertTrue(midend.solve())
            assertEquals(aux, midend.serialise().value("SOLVE"))
            assertEquals(1, midend.status())
            val solvedText = midend.serialise().serialise()
            assertEquals(solvedText, oracle.id(solvedText).state)
        }

    @Test(timeout = 120_000)
    fun `an empty seed stays an empty SEED record`() {
        val midend = Midend.newGame(Towers, "", "")
        val save = midend.serialise()
        assertEquals("", save.value("SEED"))
        assertNull(save.value("HEXSEED"))
        assertTrue(save.serialise().contains("SEED    :0:\n"))
        assertEquals(save, Midend.fromSave(Towers, SgtpSave.parse(save.serialise())).serialise())
    }
}
