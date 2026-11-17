package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Rect
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: rect.
 *
 * The save corpus in both directions, the PARAMS/CPARAMS records, the
 * stored `AUXINFO` answer, and the in-memory history. Rect writes no
 * `UI`, `TIME`, `PRIVDESC`, or `HEXSEED` record.
 */
class RectSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val saves = File(testdata, "saves/initial/rect")
    private val presets = Rect.presets().map { it.params }
    private val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")

    @Test
    fun `every corpus save round-trips and reloads in C`() = OracleClient().use { oracle ->
        val files = saves.listFiles { file -> file.extension == "sgtp" }!!
            .sortedBy { it.name }
        assertEquals(6, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertEquals("canonical text of ${file.name}", text, save.serialise())

            val midend = Midend.fromSave(Rect, save)
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
                val text = Midend.newGame(Rect, params, seed).serialise().serialise()
                val state = oracle.id(text)
                assertEquals("$params '$seed' id", text.saveId(), state.id)
                assertEquals("$params '$seed' status", 0, state.status)
                val reloaded = oracle.id(state.state)
                assertEquals("$params '$seed' canonical", state.state, reloaded.state)
                assertNotNull("$params '$seed' AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
            }
        }
    }

    @Test
    fun `the params split keeps the full form in both records`() {
        val table = mapOf(
            "default" to ("7x7" to "7x7"),
            "7x7" to ("7x7" to "7x7"),
            "9x9" to ("9x9" to "9x9"),
            "11x11" to ("11x11" to "11x11"),
            "13x13" to ("13x13" to "13x13"),
            "15x15" to ("15x15" to "15x15"),
        )
        for ((name, expected) in table) {
            val save = SgtpSave.parse(File(saves, "$name.sgtp").readText())
            assertEquals("$name PARAMS", expected.first, save.value("PARAMS"))
            assertEquals("$name CPARAMS", expected.second, save.value("CPARAMS"))
        }
        // The empty parameter entry is byte-identical to the default.
        assertEquals(
            File(saves, "7x7.sgtp").readText(),
            File(saves, "default.sgtp").readText(),
        )

        // The short encoder drops `a` and `e`, so the long-term record
        // keeps the base size while the short-term record keeps the
        // chosen mode.
        for ((params, seed) in listOf("7x7a" to "1", "7x7e0.5" to "12345")) {
            val save = Midend.newGame(Rect, params, seed).serialise()
            assertEquals("$params PARAMS", "7x7", save.value("PARAMS"))
            assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
        }
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
        val params = "7x7"
        val base = oracle.newGame("rect", params, SEED)
        val tile = 24
        val midend = Midend.fromSave(Rect, SgtpSave.parse(base.state))

        // One edge click: the edge midpoint between grid vertices
        // (1, 1) and (2, 1) is at view (38, 26) for tile 24 and border 2.
        val edgePoint = listOf(
            OracleClient.Point(38, 26, Buttons.LEFT_BUTTON),
            OracleClient.Point(38, 26, Buttons.LEFT_RELEASE),
        )
        val clicked = oracle.pointsSized(base.state, edgePoint)
        for (p in edgePoint) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("click save", clicked.state.state, midend.serialise().serialise())
        assertEquals("click move", "H1,1", SgtpSave.parse(clicked.state.state).value("MOVE"))
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // One drag: press, two drags, release. The result is one `R`.
        val drag = listOf(
            OracleClient.Point(38, 26, Buttons.LEFT_BUTTON),
            OracleClient.Point(50, 38, Buttons.LEFT_DRAG),
            OracleClient.Point(62, 50, Buttons.LEFT_DRAG),
            OracleClient.Point(62, 50, Buttons.LEFT_RELEASE),
        )
        val dragged = oracle.pointsSized(clicked.state.state, drag)
        for (p in drag) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("drag save", dragged.state.state, midend.serialise().serialise())
        assertEquals(
            "drag move",
            "R1,1,2,1",
            SgtpSave.parse(dragged.state.state).values("MOVE").last(),
        )

        // The C save with two moves reloads into the same state and
        // history position.
        val fromC = Midend.fromSave(Rect, SgtpSave.parse(dragged.state.state))
        assertEquals("C save id", dragged.state.id, fromC.id())
        assertEquals("C save state", dragged.state.state, fromC.serialise().serialise())
        assertTrue("C save canUndo", fromC.canUndo)

        // Undo and redo keep the records and the position.
        val undone = oracle.undo(dragged.state.state)
        assertTrue(midend.undo())
        assertEquals("undo save", undone.state, midend.serialise().serialise())
        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertEquals("redo save", redone.state, midend.serialise().serialise())

        // A solve payload is a MOVE, and the cheated flag is not
        // serialised.
        val solve = SgtpSave.parse(oracle.solve(base.state).state).value("SOLVE")!!
        val solvedSave = appendMove(base.state, solve)
        val solved = oracle.id(solvedSave)
        val solvedMidend = Midend.fromSave(Rect, SgtpSave.parse(solvedSave))
        assertEquals("solved id", solved.id, solvedMidend.id())
        assertEquals("solved status", solved.status, solvedMidend.status())
        assertEquals("solved save", solved.state, solvedMidend.serialise().serialise())
        // A cheated solve is never marked completed in C.
        assertEquals("solved status value", 0, solvedMidend.status())
    }

    private fun appendMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "MOVE    :${move.length}:$move\n"
    }

    private fun String.saveId(): String {
        val save = SgtpSave.parse(this)
        val p = Rect.decodeParams(save.value("CPARAMS")!!) as Rect.Params
        return Rect.encodeParams(p, false) + ":" + save.value("DESC")
    }

    private companion object {
        const val SEED = "12345"
    }
}
