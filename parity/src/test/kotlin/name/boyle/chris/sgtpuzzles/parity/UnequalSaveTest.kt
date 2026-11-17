package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unequal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * unequal.
 *
 * The thirteen frozen initial saves round-trip byte for byte in both
 * directions. Unequal writes `AUXINFO`, no `UI`, no `TIME`, no
 * `PRIVDESC`, and no `HEXSEED`, and keeps the `PARAMS`/`CPARAMS` split:
 * the long-term copy is the short form decoded over the defaults, so a
 * Tricky request has an Easy `PARAMS` while `CPARAMS` keeps the request.
 *
 * The history records `P`, `R`, `F`, `M`, `H`, `SOLVE`, and `RESTART`
 * are replayable, so the spent toggles and the solver hints survive a
 * round trip.
 */
class UnequalSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun save(params: String = "5de", seed: String = SEED): String =
        Midend.newGame(Unequal, params, seed).serialise().serialise()

    /** The centre of the band to the right of a cell at tile size 48. */
    private fun rightBand(x: Int, y: Int): Pair<Int, Int> {
        val border = 24
        val square = 72
        return Pair(border + x * square + 48 + 12, border + y * square + 24)
    }

    private fun handSave(
        desc: String,
        params: String = "4de",
        auxInfo: String? = null,
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection"
        records += "VERSION" to "1"
        records += "GAME" to "Unequal"
        records += "PARAMS" to params
        records += "CPARAMS" to params
        records += "SEED" to "1"
        records += "DESC" to desc
        if (auxInfo != null) records += "AUXINFO" to auxInfo
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        records += history
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    @Test(timeout = 120_000)
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/unequal").listFiles()
                ?.filter { it.isFile && it.extension == "sgtp" }
                ?.sorted()
                ?: emptyList()
            assertEquals("twelve presets plus the default", 13, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())

                val save = SgtpSave.parse(text)
                val rewritten = midend.serialise().serialise()
                assertEquals(
                    "${file.name} raw AUXINFO",
                    save.value("AUXINFO"),
                    SgtpSave.parse(rewritten).value("AUXINFO"),
                )
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

            assertEquals(
                "the default save is the Easy save",
                File(testdata, "saves/initial/unequal/4de.sgtp").readText(),
                File(testdata, "saves/initial/unequal/default.sgtp").readText(),
            )
        }

    @Test(timeout = 120_000)
    fun `the PARAMS and CPARAMS split pins every short form`() {
        val expected = mapOf(
            "4de" to "4de",
            "5de" to "5de",
            "5dk" to "5de",
            "5dx" to "5de",
            "5adk" to "5ade",
            "6de" to "6de",
            "6dk" to "6de",
            "6dx" to "6de",
            "6adk" to "6ade",
            "7dk" to "7de",
            "7dx" to "7de",
            "7adk" to "7ade",
        )
        for ((params, shortForm) in expected) {
            val save = SgtpSave.parse(save(params, SEED))
            assertEquals("$params PARAMS", shortForm, save.value("PARAMS"))
            assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
            assertTrue("$params AUXINFO", save.value("AUXINFO")!!.isNotEmpty())
            assertFalse("$params has no d in the short id", save.value("PARAMS")!!.contains('d').not())
        }
        // The short id keeps the mode but drops the difficulty.
        val adj = Midend.newGame(Unequal, "7adk", SEED)
        assertEquals("7a", adj.id().substringBefore(':'))
    }

    @Test(timeout = 600_000)
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Unequal.presets().map { it.params } + "") {
                for (seed in seeds) {
                    val midend = Midend.newGame(Unequal, params, seed)
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
            val start = oracle.newGame("unequal", "5de", SEED).state
            val save = SgtpSave.parse(start)
            val desc = save.value("DESC")!!

            val history = ArrayList<String>()
            history += start

            // A real digit move.
            val digit = oracle.keys(start, listOf(Buttons.CURSOR_UP, '3'.code)).state
            history += digit

            // A pencil move from a right click and a digit.
            val right = listOf(
                OracleClient.Point(168, 168, Buttons.RIGHT_BUTTON),
                OracleClient.Point(168, 168, Buttons.RIGHT_RELEASE),
                OracleClient.Point(0, 0, '2'.code),
            )
            history += oracle.pointsSized(start, right, 384, 384).state.state

            // A spent clue toggle from a modified cursor move.
            val spentSequence = List(2) { Buttons.CURSOR_RIGHT } +
                List(2) { Buttons.CURSOR_DOWN } +
                listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT)
            history += oracle.keys(start, spentSequence).state

            // A real clue band cycle: the first press spends the clue
            // and the second clears the spent bit.
            val (bandX, bandY) = rightBand(2, 2)
            val bandClick = listOf(
                OracleClient.Point(bandX, bandY, Buttons.LEFT_BUTTON),
                OracleClient.Point(bandX, bandY, Buttons.LEFT_RELEASE),
            )
            val spentClick = oracle.pointsSized(start, bandClick, 384, 384).state
            history += spentClick.state
            val cycled = oracle.pointsSized(start, bandClick + bandClick, 384, 384).state
            history += cycled.state

            // An M move, an H move, and a solve.
            history += oracle.keys(start, listOf('M'.code)).state
            history += oracle.keys(start, listOf('H'.code)).state
            history += oracle.solve(start).state

            for (text in history) {
                val midend = Midend.fromSave(Unequal, SgtpSave.parse(text))
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
                assertTrue("AUXINFO", midend.serialise().value("AUXINFO") != null)
            }

            // The in-memory shapes: a digit, a pencil mark, a spent
            // toggle, an H, an M, a live redo branch, a solve, and a
            // restart.
            val live = Midend.fromSave(Unequal, save)
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

            val pencil = Midend.fromSave(Unequal, save)
            pencil.processKey(168, 168, Buttons.RIGHT_BUTTON, 48)
            pencil.processKey(168, 168, Buttons.RIGHT_RELEASE, 48)
            pencil.processKey(0, 0, '2'.code, 48)
            assertEquals("P2,2,2", pencil.serialise().values("MOVE").last())
            assertEquals(0, pencil.status())
            val pencilText = pencil.serialise().serialise()
            assertEquals(pencilText, oracle.id(pencilText).state)

            val spent = Midend.fromSave(Unequal, save)
            for (key in spentSequence) spent.processKey(0, 0, key, 48)
            assertEquals("F2,2,2048", spent.serialise().values("MOVE").last())
            val spentText = spent.serialise().serialise()
            assertEquals(spentText, oracle.id(spentText).state)
            assertTrue(
                (spent.state as Unequal.State).flags[12] and Unequal.F_SPENT_RIGHT != 0,
            )

            val clicked = Midend.fromSave(Unequal, save)
            for (point in bandClick) {
                clicked.processKey(point.x, point.y, point.button, 48)
            }
            assertEquals("F2,2,2048", clicked.serialise().values("MOVE").last())
            assertEquals(spentClick.state, clicked.serialise().serialise())
            assertTrue(clicked.canUndo)
            for (point in bandClick) {
                clicked.processKey(point.x, point.y, point.button, 48)
            }
            assertEquals(cycled.state, clicked.serialise().serialise())
            assertEquals(
                0,
                (clicked.state as Unequal.State).flags[12] and Unequal.F_SPENT_RIGHT,
            )

            val hinted = Midend.fromSave(Unequal, save)
            hinted.processKey(0, 0, 'H'.code, 48)
            assertEquals("H", hinted.serialise().values("MOVE").last())
            val hintedText = hinted.serialise().serialise()
            assertEquals(hintedText, oracle.id(hintedText).state)
            assertTrue((hinted.state as Unequal.State).hints.any { it })

            val marked = Midend.fromSave(Unequal, save)
            marked.processKey(0, 0, 'M'.code, 48)
            assertEquals("M", marked.serialise().values("MOVE").last())
            assertEquals(0, marked.status())
            val markedText = marked.serialise().serialise()
            assertEquals(markedText, oracle.id(markedText).state)

            val solved = Midend.fromSave(Unequal, save)
            assertTrue(solved.solve())
            assertEquals(1, solved.status())
            assertEquals(
                SgtpSave.parse(oracle.solve(start).state).serialise(),
                solved.serialise().serialise(),
            )

            val restarted = Midend.fromSave(Unequal, save)
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
    fun `a hand-written answer round-trips and the solve uses it`() =
        OracleClient().use { oracle ->
            val first = Midend.newGame(Unequal, "5de", "1").serialise()
            val second = Midend.newGame(Unequal, "5de", "2").serialise()
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
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(text))
            assertEquals(second.value("AUXINFO"), midend.serialise().value("AUXINFO"))

            val expected = oracle.solve(text)
            val aux = requireNotNull(midend.auxInfo)
            assertTrue(midend.solve())
            assertEquals(aux, midend.serialise().value("SOLVE"))
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(expected.status, midend.status())
        }

    @Test(timeout = 120_000)
    fun `an empty seed stays an empty SEED record`() {
        val midend = Midend.newGame(Unequal, "", "")
        val save = midend.serialise()
        assertEquals("", save.value("SEED"))
        assertNull(save.value("HEXSEED"))
        assertTrue(save.serialise().contains("SEED    :0:\n"))
        assertEquals(save, Midend.fromSave(Unequal, SgtpSave.parse(save.serialise())).serialise())
    }

    @Test(timeout = 240_000)
    fun `a corrupt move is rejected on load`() {
        val start = save("4de")
        val save = SgtpSave.parse(start)
        for (move in listOf("R0,0,99", "F0,0,16384", "F0,0,-1", "R0,0", "")) {
            val records = save.records.map {
                when (it.key) {
                    "NSTATES" -> SaveRecord("NSTATES", "2")
                    "STATEPOS" -> SaveRecord("STATEPOS", "2")
                    else -> it
                }
            } + SaveRecord("MOVE", move)
            val text = SgtpSave.of(records).serialise()
            val error = try {
                Midend.fromSave(Unequal, SgtpSave.parse(text))
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: IllegalStateException) {
                e.message
            }
            assertNotNull("'$move' is rejected", error)
            assertTrue("'$move' message", error!!.contains("invalid move"))
        }

        // The positive control: a valid move loads.
        val valid = save.records.map {
            when (it.key) {
                "NSTATES" -> SaveRecord("NSTATES", "2")
                "STATEPOS" -> SaveRecord("STATEPOS", "2")
                else -> it
            }
        } + SaveRecord("MOVE", "R0,0,3")
        val loaded = Midend.fromSave(Unequal, SgtpSave.parse(SgtpSave.of(valid).serialise()))
        assertEquals("R0,0,3", loaded.serialise().values("MOVE").last())
    }

    @Test(timeout = 120_000)
    fun `S ignores immutability when a save replays it`() =
        OracleClient().use { oracle ->
            // The 4de golden has an immutable 3 at (2,2). A hand-written
            // S move overwrites it, because execute_move has no
            // immutability guard.
            val start = save("4de")
            val desc = SgtpSave.parse(start).value("DESC")!!
            val text = handSave(
                desc,
                "4de",
                history = listOf("SOLVE" to "S" + "1".repeat(16)),
            )
            val midend = Midend.fromSave(Unequal, SgtpSave.parse(text))
            val oracleState = oracle.status(text)
            assertEquals("S parse", oracleState.id, midend.id())
            assertEquals("S status", oracleState.status, midend.status())
            assertEquals("S text", oracle.text(text), midend.textFormat())
            assertEquals(
                "the immutable 3 was overwritten",
                1,
                (midend.state as Unequal.State).nums[10],
            )
        }

    private companion object {
        const val SEED = "12345"
    }
}
