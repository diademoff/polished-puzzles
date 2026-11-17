package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: keen.
 *
 * The eleven frozen initial saves round-trip byte for byte in both
 * directions. Keen writes `AUXINFO`, no `UI`, no `TIME`, and no
 * `PRIVDESC`, and it keeps the `PARAMS`/`CPARAMS` split: the long-term
 * copy is the bare width, so its difficulty is Normal and its flag is
 * off.
 */
class KeenSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 120_000)
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Keen.name }
                .sorted()
                .toList()
            assertEquals("ten presets plus the default", 11, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Keen, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), "")
                assertEquals("${file.name} text format", "", midend.textFormat())

                val save = SgtpSave.parse(text)
                val aux = save.value("AUXINFO")!!
                assertTrue("${file.name} AUXINFO", aux.isNotEmpty())
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

            // The default save is byte-identical to the Normal save.
            assertEquals(
                File(testdata, "saves/initial/keen/6dn.sgtp").readText(),
                File(testdata, "saves/initial/keen/default.sgtp").readText(),
            )

            // The multiplication-only presets keep the split and the
            // state flag differs between a new game and a load.
            for ((file, params) in listOf(
                "5dem.sgtp" to "5dem",
                "6dnm.sgtp" to "6dnm",
            )) {
                val text = File(testdata, "saves/initial/keen/$file").readText()
                val save = SgtpSave.parse(text)
                assertEquals("$file PARAMS", params.substringBefore('d') + "dn", save.value("PARAMS"))
                assertEquals("$file CPARAMS", params, save.value("CPARAMS"))
                assertTrue(
                    "$file loaded state flag",
                    (Midend.fromSave(Keen, save).state as Keen.State)
                        .params.multiplicationOnly,
                )
            }
        }

    @Test(timeout = 600_000)
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Keen.presets().map { it.params } + "") {
                for (seed in seeds) {
                    val midend = Midend.newGame(Keen, params, seed)
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
                }
            }
        }

    @Test(timeout = 300_000)
    fun `a played history round-trips in both directions`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("keen", "6dn", "12345").state
            val save = SgtpSave.parse(start)
            val desc = save.value("DESC")!!
            val aux = requireNotNull(Midend.fromSave(Keen, save).auxInfo)

            fun clickAndDigit(cell: Int, digit: Char): List<OracleClient.Point> {
                val w = 6
                val x = 24 + (cell % w) * 48 + 2
                val y = 24 + (cell / w) * 48 + 2
                return listOf(
                    OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
                    OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
                    OracleClient.Point(0, 0, digit.code),
                )
            }

            val history = ArrayList<String>()
            history += start

            // A real digit move.
            val digitPoints = clickAndDigit(0, aux[1])
            history += oracle.pointsSized(start, digitPoints, 336, 336).state.state

            // A pencil move from a right click.
            val right = listOf(
                OracleClient.Point(26, 26, Buttons.RIGHT_BUTTON),
                OracleClient.Point(26, 26, Buttons.RIGHT_RELEASE),
                OracleClient.Point(0, 0, '3'.code),
            )
            history += oracle.pointsSized(start, right, 336, 336).state.state

            // A solve.
            history += oracle.solve(start).state

            for (text in history) {
                val midend = Midend.fromSave(Keen, SgtpSave.parse(text))
                val loaded = oracle.id(text)
                assertEquals("round trip", text, midend.serialise().serialise())
                assertEquals("oracle", loaded.state, midend.serialise().serialise())
                assertEquals(
                    "undo flag of ${SgtpSave.parse(text).value("MOVE") ?: "start"}",
                    loaded.canUndo,
                    midend.canUndo,
                )
                assertEquals(
                    "redo flag of ${SgtpSave.parse(text).value("MOVE") ?: "start"}",
                    loaded.canRedo,
                    midend.canRedo,
                )
                assertEquals("status", loaded.status, midend.status())
            }

            // The four in-memory history shapes: a digit, a pencil mark,
            // a live redo branch, a solve, a restart, and an M move.
            val live = Midend.fromSave(Keen, save)
            live.processKey(26, 26, Buttons.LEFT_BUTTON, 48)
            live.processKey(26, 26, Buttons.LEFT_RELEASE, 48)
            live.processKey(0, 0, aux[1].code, 48)
            assertEquals("R0,0,${aux[1]}", live.serialise().value("MOVE"))
            assertEquals(2, live.stateCount)
            assertTrue(live.canUndo)
            assertFalse(live.canRedo)
            val movedText = live.serialise().serialise()
            assertEquals(history[1], movedText)
            assertTrue(live.undo())
            assertTrue(live.canRedo)
            assertEquals(oracle.undo(movedText).state, live.serialise().serialise())
            assertTrue(live.redo())
            assertEquals(2, live.stateCount)
            assertEquals(movedText, live.serialise().serialise())

            val pencil = Midend.fromSave(Keen, save)
            pencil.processKey(26, 26, Buttons.RIGHT_BUTTON, 48)
            pencil.processKey(26, 26, Buttons.RIGHT_RELEASE, 48)
            pencil.processKey(0, 0, '3'.code, 48)
            assertEquals("P0,0,3", pencil.serialise().value("MOVE"))
            assertEquals(0, pencil.status())

            val solved = Midend.fromSave(Keen, save)
            assertTrue(solved.solve())
            assertEquals(1, solved.status())
            assertEquals(
                SgtpSave.parse(oracle.solve(start).state).serialise(),
                solved.serialise().serialise(),
            )

            val marked = Midend.fromSave(Keen, save)
            marked.processKey(0, 0, 'M'.code, 48)
            assertEquals("M", marked.serialise().value("MOVE"))
            assertEquals(0, marked.status())
            val markedText = marked.serialise().serialise()
            assertEquals(markedText, oracle.id(markedText).state)

            val restarted = Midend.fromSave(Keen, save)
            restarted.processKey(26, 26, Buttons.LEFT_BUTTON, 48)
            restarted.processKey(26, 26, Buttons.LEFT_RELEASE, 48)
            restarted.processKey(0, 0, aux[1].code, 48)
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
            val withAux = oracle.newGame("keen", "6dn", "12345").state
            val withoutAux = withAux.lineSequence()
                .filterNot { it.startsWith("AUXINFO") }
                .joinToString("\n", postfix = "\n")
            val expected = oracle.solve(withoutAux)
            val move = SgtpSave.parse(expected.state).value("SOLVE")!!
            assertNull(SgtpSave.parse(withoutAux).value("AUXINFO"))

            val midend = Midend.fromSave(Keen, SgtpSave.parse(withoutAux))
            assertNull(midend.auxInfo)
            assertEquals(move, Keen.solve(midend.state, null))
            assertTrue(midend.solve())
            assertEquals(expected.state, midend.serialise().serialise())
        }

    @Test(timeout = 120_000)
    fun `a hand-written answer round-trips and the solve uses it`() =
        OracleClient().use { oracle ->
            val first = Midend.newGame(Keen, "3dn", "1").serialise()
            val second = Midend.newGame(Keen, "3dn", "2").serialise()
            assertNotNull(first.value("AUXINFO"))
            assertNotNull(second.value("AUXINFO"))
            val records = first.records.map {
                if (it.key == "AUXINFO") {
                    SaveRecord("AUXINFO", second.value("AUXINFO")!!)
                } else {
                    it
                }
            }
            val text = SgtpSave.of(records).serialise()
            val midend = Midend.fromSave(Keen, SgtpSave.parse(text))
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
        val midend = Midend.newGame(Keen, "", "")
        val save = midend.serialise()
        assertEquals("", save.value("SEED"))
        assertNull(save.value("HEXSEED"))
        assertTrue(save.serialise().contains("SEED    :0:\n"))
        assertEquals(save, Midend.fromSave(Keen, SgtpSave.parse(save.serialise())).serialise())
    }
}
