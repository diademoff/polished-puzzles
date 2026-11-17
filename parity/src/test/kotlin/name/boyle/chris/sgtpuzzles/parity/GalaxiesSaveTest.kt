package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: galaxies.
 *
 * The save corpus in both directions, the PARAMS/CPARAMS records, the
 * stored `AUXINFO` answer, and the in-memory history. Galaxies writes
 * no `UI`, `TIME`, `PRIVDESC`, or `HEXSEED` record.
 */
class GalaxiesSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val saves = File(testdata, "saves/initial/galaxies")
    private val presets = Galaxies.presets().map { it.params }
    private val seeds = listOf("1", "2", "3", "12345", "seed", "0", "-7", "")

    @Test
    fun `every corpus save round-trips and reloads in C`() = OracleClient().use { oracle ->
        val files = saves.listFiles { file -> file.extension == "sgtp" }!!
            .sortedBy { it.name }
        assertEquals(7, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertEquals("canonical text of ${file.name}", text, save.serialise())

            val midend = Midend.fromSave(Galaxies, save)
            assertEquals("desc of ${file.name}", save.value("DESC"), midend.desc)
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
    fun `C loads a Kotlin written save for every preset and seed`() =
        OracleClient().use { oracle ->
            for (params in presets) {
                for (seed in seeds) {
                    val text = Midend.newGame(Galaxies, params, seed).serialise().serialise()
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
    fun `the params split keeps the normal form in the long record`() {
        val table = mapOf(
            "default" to ("7x7dn" to "7x7dn"),
            "7x7dn" to ("7x7dn" to "7x7dn"),
            "7x7du" to ("7x7dn" to "7x7du"),
            "10x10dn" to ("10x10dn" to "10x10dn"),
            "10x10du" to ("10x10dn" to "10x10du"),
            "15x15dn" to ("15x15dn" to "15x15dn"),
            "15x15du" to ("15x15dn" to "15x15du"),
        )
        for ((name, expected) in table) {
            val save = SgtpSave.parse(File(saves, "$name.sgtp").readText())
            assertEquals("$name PARAMS", expected.first, save.value("PARAMS"))
            assertEquals("$name CPARAMS", expected.second, save.value("CPARAMS"))
        }
        // The empty parameter entry is byte-identical to the default.
        assertEquals(
            File(saves, "7x7dn.sgtp").readText(),
            File(saves, "default.sgtp").readText(),
        )

        // The short encoder drops the difficulty, so a generated
        // unreasonable game writes the normal long form.
        for (params in listOf("7x7du", "10x10du", "15x15du")) {
            val save = Midend.newGame(Galaxies, params, "12345").serialise()
            assertEquals("$params PARAMS", params.substringBefore('d') + "dn", save.value("PARAMS"))
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
        val params = "7x7dn"
        val base = oracle.newGame("galaxies", params, SEED)
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(base.state))
        val tile = 32

        // A left click on the vertical edge (2,1). The space coordinate
        // x maps to `SCOORD(x) = x * tile / 2 + tile`.
        val edgePoint = listOf(
            OracleClient.Point(64, 48, Buttons.LEFT_BUTTON),
            OracleClient.Point(64, 48, Buttons.LEFT_RELEASE),
        )
        val clicked = oracle.pointsSized(base.state, edgePoint)
        for (p in edgePoint) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("click save", clicked.state.state, midend.serialise().serialise())
        assertEquals("click move", "E2,1", SgtpSave.parse(clicked.state.state).value("MOVE"))
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // A right drag from a dot to a tile makes one association move.
        val drag = rightDrag(base.state)
        val dragged = oracle.pointsSized(clicked.state.state, drag.points)
        for (p in drag.points) midend.processKey(p.x, p.y, p.button, tile)
        assertEquals("drag save", dragged.state.state, midend.serialise().serialise())
        val dragMove = SgtpSave.parse(dragged.state.state).values("MOVE").last()
        assertTrue("drag move '$dragMove'", dragMove.startsWith("A"))

        // The C save with two moves reloads into the same state and
        // history position.
        val fromC = Midend.fromSave(Galaxies, SgtpSave.parse(dragged.state.state))
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

        // A solve payload is a SOLVE record and marks the game solved.
        val solve = SgtpSave.parse(oracle.solve(base.state).state).value("SOLVE")!!
        val solvedSave = appendRecord(base.state, "SOLVE", solve)
        val solved = oracle.id(solvedSave)
        val solvedMidend = Midend.fromSave(Galaxies, SgtpSave.parse(solvedSave))
        assertEquals("solved id", solved.id, solvedMidend.id())
        assertEquals("solved status", solved.status, solvedMidend.status())
        assertEquals("solved save", solved.state, solvedMidend.serialise().serialise())
        assertEquals("solved status value", 1, solvedMidend.status())
        assertTrue("solved flag", (solvedMidend.state as Galaxies.State).usedSolve)

        // A RESTART record returns to the initial state.
        val restartedSave = appendRecord(clicked.state.state, "RESTART", base.id.substringAfter(':'))
        val restarted = oracle.id(restartedSave)
        val restartedMidend = Midend.fromSave(Galaxies, SgtpSave.parse(restartedSave))
        assertEquals("restart id", restarted.id, restartedMidend.id())
        assertEquals("restart save", restarted.state, restartedMidend.serialise().serialise())
        assertEquals("restart state", 0, restartedMidend.status())
    }

    private class Drag(val points: List<OracleClient.Point>)

    /**
     * Picks the first association of the stored answer and drags its dot
     * onto its tile. The press is at the dot centre and the release at
     * the tile centre, both at the preferred 32-pixel tile.
     */
    private fun rightDrag(save: String): Drag {
        val midend = Midend.fromSave(Galaxies, SgtpSave.parse(save))
        val aux = midend.auxInfo!!
        for (token in aux.split(';')) {
            if (!token.startsWith("a")) continue
            val parts = token.substring(1).split(',').map { it.toInt() }
            val x = parts[0]
            val y = parts[1]
            val dx = parts[2]
            val dy = parts[3]
            val points = listOf(
                OracleClient.Point(dx * 16 + 32, dy * 16 + 32, Buttons.RIGHT_BUTTON),
                OracleClient.Point(dx * 16 + 32, dy * 16 + 32, Buttons.RIGHT_DRAG),
                OracleClient.Point(
                    32 + ((x - 1) / 2) * 32 + 16,
                    32 + ((y - 1) / 2) * 32 + 16,
                    Buttons.RIGHT_RELEASE,
                ),
            )
            val probe = Midend.fromSave(Galaxies, SgtpSave.parse(save))
            for (p in points) probe.processKey(p.x, p.y, p.button, 32)
            if (probe.canUndo) return Drag(points)
        }
        error("no association of the stored answer makes a drag move")
    }

    private fun appendRecord(save: String, key: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "${key.padEnd(8)}:${move.length}:$move\n"
    }

    private fun String.saveId(): String {
        val save = SgtpSave.parse(this)
        val p = Galaxies.decodeParams(save.value("CPARAMS")!!) as Galaxies.Params
        return Galaxies.encodeParams(p, false) + ":" + save.value("DESC")
    }

    private companion object {
        const val SEED = "12345"
    }
}
