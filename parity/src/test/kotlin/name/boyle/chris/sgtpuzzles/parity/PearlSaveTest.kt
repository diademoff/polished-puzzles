package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pearl
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
 * Level: strict. Game: pearl.
 *
 * The save corpus in both directions, the PARAMS/CPARAMS split, and the
 * in-memory history records. `AUXINFO` is present for every generated
 * preset; there is no `UI`, `TIME`, `PRIVDESC`, or `HEXSEED`.
 */
class PearlSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val saves = File(testdata, "saves/initial/pearl")
    private val presets = Pearl.presets().map { it.params }
    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `every corpus save round-trips and reloads in C`() = OracleClient().use { oracle ->
        val files = saves.listFiles { file -> file.extension == "sgtp" }!!
            .sortedBy { it.name }
        assertEquals(9, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertEquals("canonical text of ${file.name}", text, save.serialise())

            val midend = Midend.fromSave(Pearl, save)
            assertEquals("id of ${file.name}", save.value("DESC"), midend.desc)
            assertEquals("status of ${file.name}", 0, midend.status())
            assertFalse("canUndo of ${file.name}", midend.canUndo)
            assertFalse("canRedo of ${file.name}", midend.canRedo)
            assertNotNull("aux of ${file.name}", midend.auxInfo)
            val oracleState = oracle.id(text)
            assertEquals("oracle id of ${file.name}", oracleState.id, midend.id())
            assertEquals("oracle text of ${file.name}", oracle.text(text), midend.textFormat())
            assertEquals(
                "oracle reload of ${file.name}",
                oracleState.state,
                midend.serialise().serialise(),
            )
        }
    }

    @Test
    fun `C loads a Kotlin written save for every preset and seed`() = OracleClient().use { oracle ->
        for (params in presets) {
            for (seed in seeds) {
                val text = Midend.newGame(Pearl, params, seed).serialise().serialise()
                val state = oracle.id(text)
                assertEquals("$params '$seed' id", text.saveId(), state.id)
                assertEquals("$params '$seed' status", 0, state.status)
                val reloaded = oracle.id(state.state)
                assertEquals("$params '$seed' canonical", state.state, reloaded.state)
            }
        }
    }

    @Test
    fun `the params split matches the table`() {
        val table = mapOf(
            "default" to Triple("8x8de", "8x8de", "dWaWcWbWWaBhWbWWdBBaWiWeWbBbWd"),
            "8x8de" to Triple("8x8de", "8x8de", "dWaWcWbWWaBhWbWWdBBaWiWeWbBbWd"),
            "8x8dt" to Triple("8x8de", "8x8dt", "aBdWfWcWeBbBdWbBbWaWcBlBbBaWWa"),
            "10x10dt" to Triple(
                "10x10de",
                "10x10dt",
                "eWaBjWaWWaBaWcWcBbWcWcBaWlWBaBWbWBaBcBbBmBcWbWaB",
            ),
            "6x6dt" to Triple("6x6de", "6x6dt", "cWaBWhWdWWbWgBbB"),
        )
        for ((name, expected) in table) {
            val save = SgtpSave.parse(File(saves, "$name.sgtp").readText())
            assertEquals("$name PARAMS", expected.first, save.value("PARAMS"))
            assertEquals("$name CPARAMS", expected.second, save.value("CPARAMS"))
            assertEquals("$name DESC", expected.third, save.value("DESC"))
        }
        // The empty parameter entry is byte-identical to 8x8de.
        assertEquals(
            File(saves, "8x8de.sgtp").readText(),
            File(saves, "default.sgtp").readText(),
        )
    }

    @Test
    fun `no corpus save has ui time private desc or hex seed`() {
        for (file in saves.listFiles { f -> f.extension == "sgtp" }!!) {
            val save = SgtpSave.parse(file.readText())
            assertNull("${file.name} UI", save.value("UI"))
            assertNull("${file.name} TIME", save.value("TIME"))
            assertNull("${file.name} PRIVDESC", save.value("PRIVDESC"))
            assertNull("${file.name} HEXSEED", save.value("HEXSEED"))
            assertNotNull("${file.name} AUXINFO", save.value("AUXINFO"))
            assertEquals("${file.name} SEED", "12345", save.value("SEED"))
        }
    }

    @Test
    fun `the in-memory history records load in C`() = OracleClient().use { oracle ->
        val tile = 31
        val border = tile / 2
        fun px(x: Int) = border + x * tile + tile / 2
        fun py(y: Int) = border + y * tile + tile / 2

        // A real click, just outside the centre radius.
        val click = Midend.newGame(Pearl, "6x6de", SEED)
        val clickY = py(0) + tile / 4 + 1
        click.processKey(px(0), clickY, Buttons.LEFT_BUTTON, tile)
        click.processKey(px(0), clickY, Buttons.LEFT_RELEASE, tile)
        checkInC(oracle, click, "click")
        assertEquals(1, click.serialise().values("MOVE").size)

        // A real drag.
        val drag = Midend.newGame(Pearl, "6x6de", SEED)
        drag.processKey(px(0), py(0), Buttons.LEFT_BUTTON, tile)
        drag.processKey(px(1), py(0), Buttons.LEFT_DRAG, tile)
        drag.processKey(px(2), py(0), Buttons.LEFT_DRAG, tile)
        drag.processKey(px(2), py(0), Buttons.LEFT_RELEASE, tile)
        checkInC(oracle, drag, "drag")
        assertEquals(1, drag.serialise().values("MOVE").size)

        // A real mark click.
        val mark = Midend.newGame(Pearl, "6x6de", SEED)
        mark.processKey(px(0), clickY, Buttons.RIGHT_BUTTON, tile)
        mark.processKey(px(0), clickY, Buttons.RIGHT_RELEASE, tile)
        checkInC(oracle, mark, "mark")
        assertTrue(
            "the mark record",
            mark.serialise().value("MOVE")!!.startsWith("M"),
        )
        assertEquals(1, mark.serialise().values("MOVE").size)

        // Undo with a live redo branch.
        val undone = Midend.fromSave(Pearl, SgtpSave.parse(drag.serialise().serialise()))
        assertTrue(undone.undo())
        assertTrue(undone.canRedo)
        checkInC(oracle, undone, "undone")
        assertTrue(undone.serialise().value("STATEPOS")!!.toInt() <
            undone.serialise().value("NSTATES")!!.toInt())

        // A SOLVE record.
        val solved = Midend.newGame(Pearl, "6x6de", SEED)
        assertTrue(solved.solve())
        checkInC(oracle, solved, "solve")
        assertNotNull(solved.serialise().value("SOLVE"))

        // An S move sets used_solve; alone it changes nothing else.
        val solveFlag = Midend.fromSave(
            Pearl,
            SgtpSave.parse(appendMove(File(saves, "6x6de.sgtp").readText(), "S")),
        )
        checkInC(oracle, solveFlag, "S move")
        assertTrue((solveFlag.state as Pearl.State).usedSolve)
        assertEquals(0, solveFlag.status())

        // An S-prefixed line move is the same C path with a visible change.
        val solveLine = Midend.fromSave(
            Pearl,
            SgtpSave.parse(
                appendMove(File(saves, "6x6de.sgtp").readText(), "S;F1,0,0;F4,1,0"),
            ),
        )
        checkInC(oracle, solveLine, "S line")
        assertTrue((solveLine.state as Pearl.State).usedSolve)

        // A RESTART record.
        val restarted = Midend.newGame(Pearl, "6x6de", SEED)
        assertEquals(
            name.boyle.chris.sgtpuzzles.engine.KeyOutcome.SomeEffect,
            restarted.processKey(px(0), py(0), 'H'.code, tile),
        )
        assertTrue(restarted.restart())
        checkInC(oracle, restarted, "restart")
        assertNotNull(restarted.serialise().value("RESTART"))

        // An empty MOVE record is accepted by execute_move.
        val empty = appendMove(File(saves, "6x6de.sgtp").readText(), "")
        val emptyState = oracle.id(empty)
        assertEquals("6x6:BaWnBBdBaWfWc", emptyState.id)
        assertEquals(1, emptyState.state.lines().count { it.startsWith("MOVE") })
        val emptyKotlin = Midend.fromSave(Pearl, SgtpSave.parse(empty))
        assertEquals(2, emptyKotlin.stateCount)
        assertEquals("", emptyKotlin.serialise().value("MOVE"))

        // An H MOVE record replays the solver.
        val hint = appendMove(File(saves, "6x6de.sgtp").readText(), "H")
        val hintState = oracle.id(hint)
        assertEquals(1, hintState.status)
        val hintKotlin = Midend.fromSave(Pearl, SgtpSave.parse(hint))
        assertEquals(1, hintKotlin.status())
        assertEquals(
            oracle.text(hint),
            hintKotlin.textFormat(),
        )
        assertFalse((hintKotlin.state as Pearl.State).usedSolve)
    }

    @Test
    fun `a save without auxinfo solves through the solver`() = OracleClient().use { oracle ->
        val base = File(saves, "6x6de.sgtp").readText()
        val noAux = SgtpSave.of(
            SgtpSave.parse(base).records.filter { it.key != "AUXINFO" },
        ).serialise()
        val cSolved = oracle.solve(noAux)
        val payload = SgtpSave.parse(cSolved.state).value("SOLVE")
        assertNotNull(payload)

        val midend = Midend.fromSave(Pearl, SgtpSave.parse(noAux))
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertEquals(payload, midend.serialise().value("SOLVE"))
        checkInC(oracle, midend, "no aux solve")
    }

    @Test
    fun `a missing description is rejected on both sides`() = OracleClient().use { oracle ->
        val base = File(saves, "6x6de.sgtp").readText()
        val noDesc = base.split('\n').filterNot { it.startsWith("DESC") }.joinToString("\n") + "\n"
        val cError = try {
            oracle.id(noDesc)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Game description in save file is missing", cError)
        val kError = runCatching { Midend.fromSave(Pearl, SgtpSave.parse(noDesc)) }
            .exceptionOrNull()?.message
        assertEquals("game description in save file is missing", kError)
    }

    private fun checkInC(oracle: OracleClient, midend: Midend, what: String) {
        val text = midend.serialise().serialise()
        val state = oracle.id(text)
        assertEquals("$what id", midend.id(), state.id)
        assertEquals("$what status", midend.status(), state.status)
        assertEquals("$what canUndo", midend.canUndo, state.canUndo)
        assertEquals("$what canRedo", midend.canRedo, state.canRedo)
        assertEquals("$what text", midend.textFormat(), oracle.text(text))
        assertEquals("$what canonical", text, state.state)
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private fun String.saveId(): String {
        val save = SgtpSave.parse(this)
        return save.value("CPARAMS")!!.decodeShort() + ":" + save.value("DESC")
    }

    private fun String.decodeShort(): String {
        val p = Pearl.decodeParams(this) as Pearl.Params
        return Pearl.encodeParams(p, false)
    }

    private companion object {
        const val SEED = "12345"
    }
}
