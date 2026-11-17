package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Solo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: solo.
 *
 * The save corpus in both directions, the `PARAMS`/`CPARAMS` split,
 * the stored `AUXINFO` answer, and the in-memory history. Solo writes
 * no `UI`, `TIME`, `PRIVDESC`, or `HEXSEED` record.
 */
class SoloSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )
    private val saves = File(testdata, "saves/initial/solo")
    private val presets = Solo.presets().map { it.params }
    private val seeds = listOf("1", "2", "3", "12345", "seed", "0", "-7", "")

    @Test(timeout = 120_000)
    fun `every corpus save round-trips and reloads in C`() = OracleClient().use { oracle ->
        val files = saves.listFiles { file -> file.extension == "sgtp" }!!
            .sortedBy { it.name }
        assertEquals(17, files.size)
        for (file in files) {
            val text = file.readText()
            val save = SgtpSave.parse(text)
            assertEquals("canonical text of ${file.name}", text, save.serialise())

            val midend = Midend.fromSave(Solo, save)
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

    @Test(timeout = 600_000)
    fun `C loads a Kotlin written save for every preset and seed`() =
        OracleClient().use { oracle ->
            for (params in presets) {
                for (seed in seeds) {
                    val text = Midend.newGame(Solo, params, seed).serialise().serialise()
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
    fun `the params split keeps the short form in the long record`() {
        val table = mapOf(
            "default" to ("3x3" to "3x3"),
            "2x2" to ("2x2" to "2x2"),
            "2x3db" to ("2x3" to "2x3db"),
            "3x3" to ("3x3" to "3x3"),
            "3x3da" to ("3x3" to "3x3da"),
            "3x3db" to ("3x3" to "3x3db"),
            "3x3de" to ("3x3" to "3x3de"),
            "3x3di" to ("3x3" to "3x3di"),
            "3x3du" to ("3x3" to "3x3du"),
            "3x3ka" to ("3x3k" to "3x3ka"),
            "3x3xda" to ("3x3x" to "3x3xda"),
            "3x3xdb" to ("3x3x" to "3x3xdb"),
            "3x4db" to ("3x4" to "3x4db"),
            "4x4db" to ("4x4" to "4x4db"),
            "9jda" to ("9j" to "9jda"),
            "9jdb" to ("9j" to "9jdb"),
            "9jxdb" to ("9jx" to "9jxdb"),
        )
        for ((name, expected) in table) {
            val save = SgtpSave.parse(File(saves, "$name.sgtp").readText())
            assertEquals("$name PARAMS", expected.first, save.value("PARAMS"))
            assertEquals("$name CPARAMS", expected.second, save.value("CPARAMS"))
        }
        assertEquals(
            File(saves, "3x3.sgtp").readText(),
            File(saves, "default.sgtp").readText(),
        )

        // The short encoder drops the difficulty and symmetry, so a
        // generated advanced or symmetric game writes the trivial long
        // form.
        for (params in listOf("3x3da", "3x3de", "3x3du", "3x3m8db")) {
            val save = Midend.newGame(Solo, params, "12345").serialise()
            assertEquals("$params PARAMS", "3x3", save.value("PARAMS"))
            assertTrue("$params CPARAMS", save.value("CPARAMS")!!.startsWith("3x3"))
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

    @Test(timeout = 300_000)
    fun `the in-memory history records load in C`() = OracleClient().use { oracle ->
        val base = oracle.newGame("solo", "3x3", SEED)
        val midend = Midend.fromSave(Solo, SgtpSave.parse(base.state))

        // Click the empty cell (4,4), then enter 5.
        val points = listOf(
            OracleClient.Point(240, 240, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, '5'.code),
        )
        val played = oracle.pointsSized(base.state, points)
        for (p in points) midend.processKey(p.x, p.y, p.button, 48)
        assertEquals("play save", played.state.state, midend.serialise().serialise())
        assertEquals("play text", oracle.text(played.state.state), midend.textFormat())
        assertEquals("play move", "R4,4,5", SgtpSave.parse(played.state.state).value("MOVE"))
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)

        // Undo and redo keep the records and the position.
        val undone = oracle.undo(played.state.state)
        assertTrue(midend.undo())
        assertEquals("undo save", undone.state, midend.serialise().serialise())
        assertEquals("undo text", oracle.text(undone.state), midend.textFormat())
        val redone = oracle.redo(undone.state)
        assertTrue(midend.redo())
        assertEquals("redo save", redone.state, midend.serialise().serialise())

        // A solve append a SOLVE record and marks the game solved.
        val solved = oracle.solve(played.state.state)
        assertTrue(midend.solve())
        assertEquals("solve save", solved.state, midend.serialise().serialise())
        assertEquals("solve status", 1, solved.status)
        assertEquals("solve status", solved.status, midend.status())

        // A RESTART record returns to the initial state.
        val restartedSave = appendRecord(played.state.state, "RESTART", base.id.substringAfter(':'))
        val restarted = oracle.id(restartedSave)
        val restartedMidend = Midend.fromSave(Solo, SgtpSave.parse(restartedSave))
        assertEquals("restart id", restarted.id, restartedMidend.id())
        assertEquals("restart save", restarted.state, restartedMidend.serialise().serialise())
        assertEquals("restart state", 0, restartedMidend.status())
        assertEquals("restart text", oracle.text(restarted.state), restartedMidend.textFormat())

        // A Kotlin save with the move reloads in C and in Kotlin.
        val fromC = Midend.fromSave(Solo, SgtpSave.parse(played.state.state))
        assertEquals("C save id", played.state.id, fromC.id())
        assertEquals("C save state", played.state.state, fromC.serialise().serialise())
        assertTrue("C save canUndo", fromC.canUndo)
    }

    @Test(timeout = 120_000)
    fun `a killer save with an unclued cage is a defined port result`() =
        OracleClient().use { oracle ->
            // The vendored solver and drawing assert that every cage has
            // a clue (`solo.c:1749`, `3025`). A hand-written killer
            // description can reach that assert; C loads the save and
            // reports the same id before it draws or solves. D-024
            // defines the port result.
            val desc = "p,a______aa_baa_aa,b_4_6_7_7d_6b_5b"
            val save = handSave("2x2k", "2x2ka", desc)
            val expected = oracle.id(save)
            val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
            assertEquals("no-clue id", expected.id, midend.id())
            assertEquals("no-clue save", expected.state, midend.serialise().serialise())
            assertEquals("no-clue status", 0, midend.status())
            assertEquals("killer text form", "", midend.textFormat())
            // The solver treats the missing clue as zero instead of
            // aborting. Without the clue the board is ambiguous, so the
            // solve returns null; a stored answer is still returned
            // unchanged, as `solve_game` does.
            val solved = Solo.solve(midend.state, null)
            assertNull("no-clue solve", solved)
            assertEquals(
                "stored answer",
                "S3,2,1,4,1,4,3,2,4,3,2,1,2,1,4,3",
                Solo.solve(midend.state, "S3,2,1,4,1,4,3,2,4,3,2,1,2,1,4,3"),
            )
            // A full grid with the unclued cage never completes.
            val full = Solo.newState(Solo.decodeParams("2x2k"), fullGridDesc)
            assertFalse("a full unclued board is open", (full as Solo.State).completed)
            val moved = Solo.executeMove(full, "R0,0,3")
            assertNotNull("a same-value move is accepted", moved)
            assertFalse("a full unclued board stays open", (moved as Solo.State).completed)
        }

    @Test
    fun `the auxinfo round-trips`() {
        val text = Midend.newGame(Solo, "3x3ka", "12345").serialise().serialise()
        val save = SgtpSave.parse(text)
        val decoded = midendAux(text)
        assertNotNull(decoded)
        assertTrue("the stored answer is an S move", decoded!!.startsWith("S"))
        val rewritten = SgtpSave.parse(text).serialise()
        assertEquals("the save is canonical", text, rewritten)
    }

    private fun midendAux(text: String): String? =
        Midend.fromSave(Solo, SgtpSave.parse(text)).auxInfo

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
        val p = Solo.decodeParams(save.value("CPARAMS")!!)
        return Solo.encodeParams(p, false) + ":" + save.value("DESC")
    }

    private fun handSave(params: String, cparams: String, desc: String): String = buildString {
        append("SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n")
        append("VERSION :1:1\n")
        append("GAME    :4:Solo\n")
        append("PARAMS  :${params.length}:$params\n")
        append("CPARAMS :${cparams.length}:$cparams\n")
        append("DESC    :${desc.length}:$desc\n")
        append("NSTATES :1:1\n")
        append("STATEPOS:1:1\n")
    }

    private companion object {
        const val SEED = "12345"

        /** The solved grid of `2x2ka` seed 12345, with the first clue
         *  cleared from the clue grid of the no-clue save. */
        const val fullGridDesc =
            "3_2_1_4_1_4_3_2_4_3_2_1_2_1_4_3,a______aa_baa_aa,b_4_6_7_7d_6b_5b"
    }
}
