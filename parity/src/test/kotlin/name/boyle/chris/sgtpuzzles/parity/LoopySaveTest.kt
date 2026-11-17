package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: loopy.
 *
 * The save corpus in both directions, the PARAMS and CPARAMS records,
 * and the in-memory history. Loopy writes no `UI`, `TIME`, `PRIVDESC`,
 * `HEXSEED`, or `AUXINFO` record.
 */
class LoopySaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val saves = File(testdata, "saves/initial/loopy")
    private val presets = Loopy.presets().map { it.params }
    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `every corpus save round-trips and reloads in C`() = OracleClient().use { oracle ->
        val files = saves.listFiles { file -> file.extension == "sgtp" }!!
            .sortedBy { it.name }
        assertEquals(21, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertEquals("canonical text of ${file.name}", text, save.serialise())

            val midend = Midend.fromSave(Loopy, save)
            assertEquals("id of ${file.name}", save.value("DESC"), midend.desc)
            assertEquals("status of ${file.name}", 0, midend.status())
            assertFalse("canUndo of ${file.name}", midend.canUndo)
            assertFalse("canRedo of ${file.name}", midend.canRedo)
            assertNull("aux of ${file.name}", midend.auxInfo)
            val oracleState = oracle.id(text)
            assertEquals("oracle id of ${file.name}", oracleState.id, midend.id())
            assertEquals(
                "oracle reload of ${file.name}",
                oracleState.state,
                midend.serialise().serialise(),
            )
            val p = midend.currentParams as Loopy.Params
            if (p.type == 0) {
                assertEquals("oracle text of ${file.name}", oracle.text(text), midend.textFormat())
            } else {
                assertEquals("text gate of ${file.name}", "", midend.textFormat())
            }
        }
    }

    @Test
    fun `C loads a Kotlin written save for every preset and seed`() = OracleClient().use { oracle ->
        for (params in presets) {
            for (seed in seeds) {
                val text = Midend.newGame(Loopy, params, seed).serialise().serialise()
                val state = oracle.id(text)
                assertEquals("$params '$seed' id", text.saveId(), state.id)
                assertEquals("$params '$seed' status", 0, state.status)
                val reloaded = oracle.id(state.state)
                assertEquals("$params '$seed' canonical", state.state, reloaded.state)
            }
        }
    }

    @Test
    fun `the params split keeps the long term difficulty at easy`() {
        // The long-term `PARAMS` record decodes the preset id without its
        // `d` suffix, so it keeps the width, height, and grid type but
        // the easy difficulty. `CPARAMS` carries the chosen difficulty.
        val table = mapOf(
            "default" to ("7x7t0de" to "7x7t0de"),
            "7x7t0de" to ("7x7t0de" to "7x7t0de"),
            "7x7t0dh" to ("7x7t0de" to "7x7t0dh"),
            "7x7t1dh" to ("7x7t1de" to "7x7t1dh"),
            "5x4t14dh" to ("5x4t14de" to "5x4t14dh"),
            "3x2t13dh" to ("3x2t13de" to "3x2t13dh"),
            "6x6t17dh" to ("6x6t17de" to "6x6t17dh"),
        )
        for ((name, expected) in table) {
            val save = SgtpSave.parse(File(saves, "$name.sgtp").readText())
            assertEquals("$name PARAMS", expected.first, save.value("PARAMS"))
            assertEquals("$name CPARAMS", expected.second, save.value("CPARAMS"))
        }
        // The empty parameter entry is byte-identical to the default.
        assertEquals(
            File(saves, "7x7t0de.sgtp").readText(),
            File(saves, "default.sgtp").readText(),
        )
    }

    @Test
    fun `no corpus save has ui time private desc aux or hex seed`() {
        for (file in saves.listFiles { f -> f.extension == "sgtp" }!!) {
            val save = SgtpSave.parse(file.readText())
            assertNull("${file.name} UI", save.value("UI"))
            assertNull("${file.name} TIME", save.value("TIME"))
            assertNull("${file.name} PRIVDESC", save.value("PRIVDESC"))
            assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
            assertNull("${file.name} HEXSEED", save.value("HEXSEED"))
            assertEquals("${file.name} SEED", "12345", save.value("SEED"))
        }
    }

    @Test
    fun `the in-memory history records load in C`() = OracleClient().use { oracle ->
        val params = "7x7t0dh"
        val base = oracle.newGame("loopy", params, SEED)
        val tile = 48
        val midend = Midend.fromSave(Loopy, SgtpSave.parse(base.state))
        val (x, y, index) = firstEdgePoint(midend, tile)
        val view = Loopy.computeSize(Loopy.decodeParams(params), tile)
        val points = listOf(
            OracleClient.Point(x, y, Buttons.LEFT_BUTTON),
            OracleClient.Point(x, y, Buttons.LEFT_RELEASE),
        )
        val moved = oracle.pointsSized(base.state, points, view.width, view.height)
        for (p in points) midend.processKey(p.x, p.y, p.button, tile)

        assertEquals("one move", 1, SgtpSave.parse(moved.state.state).values("MOVE").size)
        assertEquals("move record", "${index}y", SgtpSave.parse(moved.state.state).value("MOVE"))
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // The C save with one MOVE reloads into the same state and
        // history position.
        val fromC = Midend.fromSave(Loopy, SgtpSave.parse(moved.state.state))
        assertEquals("C save id", moved.state.id, fromC.id())
        assertEquals("C save state", moved.state.state, fromC.serialise().serialise())
        assertTrue("C save canUndo", fromC.canUndo)
        assertFalse("C save canRedo", fromC.canRedo)
        assertEquals("C save status", moved.state.status, fromC.status())

        // Undo keeps the history: the C save and the port agree on the
        // records, the position, and the state.
        val undone = oracle.undo(moved.state.state)
        assertTrue(midend.undo())
        assertEquals("undo save", undone.state, midend.serialise().serialise())
        assertEquals("undo id", undone.id, midend.id())
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)

        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertEquals("redo save", redone.state, midend.serialise().serialise())
        assertEquals("redo id", redone.id, midend.id())

        // A solve payload is a MOVE record too, and the cheated flag is
        // not serialised; the reload agrees on the state and the status.
        val solve = SgtpSave.parse(oracle.solve(base.state).state).value("SOLVE")!!
        val solvedSave = appendMove(base.state, solve)
        val solved = oracle.id(solvedSave)
        val solvedMidend = Midend.fromSave(Loopy, SgtpSave.parse(solvedSave))
        assertEquals("solved id", solved.id, solvedMidend.id())
        assertEquals("solved status", solved.status, solvedMidend.status())
        assertEquals("solved save", solved.state, solvedMidend.serialise().serialise())
    }

    private fun firstEdgePoint(midend: Midend, tile: Int): Triple<Int, Int, Int> {
        val state = midend.state as Loopy.State
        val g = state.grid
        val index = state.lines.indices.first()
        val e = g.edges[index]
        val gx = (e.dot1.x + e.dot2.x) / 2
        val gy = (e.dot1.y + e.dot2.y) / 2
        val border = tile / 2
        val x = border + (gx - g.lowestX) * tile / g.tileSize
        val y = border + (gy - g.lowestY) * tile / g.tileSize
        return Triple(x, y, index)
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
        val p = Loopy.decodeParams(save.value("CPARAMS")!!) as Loopy.Params
        return Loopy.encodeParams(p, false) + ":" + save.value("DESC")
    }

    private companion object {
        const val SEED = "12345"
    }
}
