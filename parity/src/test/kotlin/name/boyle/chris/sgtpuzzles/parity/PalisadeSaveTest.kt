package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Palisade
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
 * Level: strict for the text and semantic for the state. Game:
 * palisade.
 *
 * The save file carries `PARAMS`, `CPARAMS`, `SEED`, `DESC`,
 * `AUXINFO`, and the move history. The params shape always includes
 * `n<k>`, because `encode_params` ignores `full`. A save with an
 * `AUXINFO` solves by returning it verbatim; a save without one solves
 * from the rim.
 */
class PalisadeSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val presets = listOf("5x5n5", "8x6n6", "10x8n8", "15x12n10")

    @Test
    fun `the corpus saves round trip byte for byte`() = OracleClient().use { oracle ->
        val directory = File(testdata, "saves/initial/palisade")
        val files = directory.listFiles()!!.sortedBy { it.name }
        assertEquals(
            listOf("10x8n8.sgtp", "15x12n10.sgtp", "5x5n5.sgtp", "8x6n6.sgtp", "default.sgtp"),
            files.map { it.name },
        )
        for (file in files) {
            val text = file.readText()
            val midend = Midend.fromSave(Palisade, SgtpSave.parse(text))
            assertEquals("${file.name} rewrite", text, midend.serialise().serialise())

            val save = SgtpSave.parse(text)
            assertEquals("${file.name} params shape", save.value("PARAMS"), save.value("CPARAMS"))
            assertNotNull("${file.name} aux", save.value("AUXINFO"))
            assertNull("${file.name} UI record", save.value("UI"))
            assertTrue("${file.name} id has n<k>", midend.id().contains("n"))

            val loaded = oracle.id(text)
            assertEquals("${file.name} id", loaded.id, midend.id())
            assertEquals("${file.name} status", loaded.status, midend.status())
            assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
            assertEquals("${file.name} resave", text, loaded.state)
        }
        assertEquals(
            File(directory, "default.sgtp").readText(),
            File(directory, "5x5n5.sgtp").readText(),
        )
    }

    @Test
    fun `a Kotlin save loads in C for every preset`() = OracleClient().use { oracle ->
        for (params in presets) {
            val midend = Midend.newGame(Palisade, params, SEED)
            val save = midend.serialise().serialise()
            val loaded = oracle.id(save)
            assertEquals("$params id", midend.id(), loaded.id)
            assertEquals("$params status", midend.status(), loaded.status)
            assertEquals("$params text", midend.textFormat(), oracle.text(loaded.state))
            assertEquals("$params resave", save, loaded.state)
        }
    }

    @Test
    fun `the history round trips in both directions`() = OracleClient().use { oracle ->
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        val aux = decodeSolve(oracle.solve(start).state)
        val honest = honestMove(aux, 5, 5)
        val cases = listOf(
            // A real click.
            listOf("F0,0,2F1,0,8"),
            // A keyboard half-grid select.
            listOf("F1,0,8F0,0,2"),
            // The empty move.
            listOf(""),
            // A one-sided border.
            listOf("F0,0,2"),
            // A both-bits border.
            listOf("F0,0,34F1,0,136"),
            // A sign in the coordinates and the flag.
            listOf("F+0,+0,+2"),
            // A flag above 255.
            listOf("F0,0,512"),
            // An S move.
            listOf("S" + "A".repeat(25)),
            // The honest completion.
            listOf(honest),
            // Two moves, then an undo position with a live redo branch.
            listOf("F0,0,2F1,0,8", "F2,2,4F2,3,1"),
        )
        for (moves in cases) {
            val save = withMoves(start, moves)
            val midend = Midend.fromSave(Palisade, SgtpSave.parse(save))
            val loaded = oracle.id(save)
            assertEquals("$moves id", loaded.id, midend.id())
            assertEquals("$moves status", loaded.status, midend.status())
            assertEquals("$moves canundo", loaded.canUndo, midend.canUndo)
            assertEquals("$moves canredo", loaded.canRedo, midend.canRedo)
            assertEquals("$moves text", oracle.text(loaded.state), midend.textFormat())
            assertEquals("$moves resave", save, midend.serialise().serialise())
            assertEquals("$moves verbatim", moves, midend.serialise().values("MOVE"))
        }

        // The middle position: the same two moves, one undone.
        val middle = withMoves(
            start,
            listOf("F0,0,2F1,0,8", "F2,2,4F2,3,1"),
            statePos = 2,
        )
        val middleLoaded = oracle.id(middle)
        val middleMidend = Midend.fromSave(Palisade, SgtpSave.parse(middle))
        assertTrue(middleLoaded.canUndo)
        assertTrue(middleLoaded.canRedo)
        assertEquals(middleLoaded.state, middleMidend.serialise().serialise())
        val redone = oracle.redo(middle)
        assertTrue(middleMidend.redo())
        assertEquals(redone.state, middleMidend.serialise().serialise())
    }

    @Test
    fun `solve records round trip`() = OracleClient().use { oracle ->
        // The aux path.
        for (params in presets) {
            val kotlin = Midend.newGame(Palisade, params, SEED)
            assertTrue(kotlin.solve())
            val save = kotlin.serialise().serialise()
            val loaded = oracle.id(save)
            assertEquals("$params aux id", kotlin.id(), loaded.id)
            assertEquals("$params aux status", 1, loaded.status)
            assertEquals("$params aux resave", save, loaded.state)

            // The computed path: the same clue board without AUXINFO.
            val withoutAux = withoutRecord(save, "AUXINFO")
            val expected = oracle.solve(withoutAux)
            val computed = Midend.fromSave(Palisade, SgtpSave.parse(withoutAux))
            assertTrue("$params computed solve", computed.solve())
            assertEquals("$params computed resave", expected.state, computed.serialise().serialise())
            assertEquals("$params computed id", expected.id, computed.id())
        }

        // A solve after a real move is based on the clues, not the
        // current borders.
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        val moved = withMoves(start, listOf("F0,0,2F1,0,8"))
        val expected = oracle.solve(moved)
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(moved))
        assertTrue(actual.solve())
        assertEquals(expected.state, actual.serialise().serialise())
    }

    @Test
    fun `a restart record round trips`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Palisade, "5x5n5", SEED)
        midend.processKey(24 + 40, 24 + 24, Buttons.LEFT_BUTTON, 48)
        midend.processKey(24 + 40, 24 + 24, Buttons.LEFT_RELEASE, 48)
        assertTrue(midend.restart())
        val save = midend.serialise().serialise()
        assertEquals(listOf(midend.desc), midend.serialise().values("RESTART"))
        val loaded = oracle.id(save)
        assertEquals("id", midend.id(), loaded.id)
        assertEquals("state", save, loaded.state)
        assertEquals("status", 0, loaded.status)
        assertEquals("canundo", true, loaded.canUndo)
        assertEquals(
            "state",
            save,
            Midend.fromSave(Palisade, SgtpSave.parse(save)).serialise().serialise(),
        )
    }

    @Test
    fun `the k equals wh split save is accepted by the short validation`() =
        OracleClient().use { oracle ->
            // PARAMS uses a normal game, CPARAMS the full area. Only the
            // short validation runs on CPARAMS, so the save loads and
            // starts completed. This is the only way a normal-looking
            // save reaches k == w*h.
            val save = SgtpSave.of(
                listOf(
                    SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                    SaveRecord("VERSION", SgtpSave.VERSION),
                    SaveRecord("GAME", Palisade.name),
                    SaveRecord("PARAMS", "5x5n5"),
                    SaveRecord("CPARAMS", "5x5n25"),
                    SaveRecord("SEED", SEED),
                    SaveRecord("DESC", ""),
                    SaveRecord("NSTATES", "1"),
                    SaveRecord("STATEPOS", "1"),
                ),
            ).serialise()
            val expected = oracle.id(save)
            assertEquals("5x5n25:", expected.id)
            assertEquals(1, expected.status)

            val actual = Midend.fromSave(Palisade, SgtpSave.parse(save))
            assertEquals("id", expected.id, actual.id())
            assertEquals("status", expected.status, actual.status())
            assertEquals("text", oracle.text(save), actual.textFormat())
            assertEquals("resave", save, actual.serialise().serialise())

            // A full PARAMS with n25 is rejected by both.
            val full = SgtpSave.of(
                listOf(
                    SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                    SaveRecord("VERSION", SgtpSave.VERSION),
                    SaveRecord("GAME", Palisade.name),
                    SaveRecord("PARAMS", "5x5n25"),
                    SaveRecord("CPARAMS", "5x5n25"),
                    SaveRecord("SEED", SEED),
                    SaveRecord("DESC", ""),
                    SaveRecord("NSTATES", "1"),
                    SaveRecord("STATEPOS", "1"),
                ),
            ).serialise()
            assertTrue(
                try {
                    oracle.id(full)
                    false
                } catch (e: OracleException) {
                    true
                },
            )
            assertTrue(
                try {
                    Midend.fromSave(Palisade, SgtpSave.parse(full))
                    false
                } catch (e: RuntimeException) {
                    true
                },
            )

            // The solve appends the rim answer.
            val solved = oracle.solve(save)
            assertTrue(actual.solve())
            assertEquals("status", 1, actual.status())
            assertEquals(
                "SIAAACH@@@BH@@@BH@@@BLDDDF",
                actual.serialise().value("SOLVE"),
            )
            assertEquals(
                "solved id",
                solved.id,
                actual.id(),
            )
            assertEquals("solved text", oracle.text(solved.state), actual.textFormat())
            // The C loader drops the seed when CPARAMS fails the full
            // validation; the portable loader keeps it. The id, status,
            // text, and SOLVE are the same.
            assertEquals("12345", actual.serialise().value("SEED"))
        }

    @Test
    fun `a garbage aux fails to solve without an abort`() = OracleClient().use { oracle ->
        // `AUXINFO:zz` decodes to one NUL byte. C's `midend_solve`
        // asserts printable ASCII and aborts, so the oracle is not
        // asked; the portable engine returns false instead.
        val start = oracle.newGame("palisade", "5x5n5", SEED).state
        val save = replaceRecord(start, "AUXINFO", "zz")
        val actual = Midend.fromSave(Palisade, SgtpSave.parse(save))
        assertNotNull(actual.auxInfo)
        assertFalse("engine fails", actual.solve())
    }

    @Test
    fun `the two preferences are not serialised`() {
        val midend = Midend.newGame(Palisade, "5x5n5", SEED)
        val before = midend.serialise()
        val ui = midend.ui as Palisade.Ui
        ui.legacyCursor = true
        ui.clearCompleteRegions = true
        val after = midend.serialise()
        assertEquals(before, after)
        assertNull(after.value("UI"))
        assertEquals("5x5n5", after.value("PARAMS"))

        // A fresh UI has the two defaults from `new_ui`.
        val fresh = Palisade.newUi(midend.state) as Palisade.Ui
        assertEquals(1, fresh.x)
        assertEquals(1, fresh.y)
        assertFalse(fresh.show)
        assertFalse(fresh.legacyCursor)
        assertFalse(fresh.clearCompleteRegions)
    }

    @Test
    fun `the seed record keeps an empty seed empty`() {
        val midend = Midend.newGame(Palisade, "5x5n5", "")
        val save = midend.serialise()
        assertEquals("", save.value("SEED"))
        assertNull(save.value("HEXSEED"))
        assertTrue(save.serialise().contains("SEED    :0:\n"))
    }

    private fun decodeSolve(save: String): String =
        requireNotNull(SgtpSave.parse(save).value("SOLVE"))

    private fun honestMove(aux: String, w: Int, h: Int): String {
        val bits = aux.substring(1)
        val out = StringBuilder()
        for (i in bits.indices) {
            val border = bits[i].code and 15
            val x = i % w
            val y = i / w
            for (dir in listOf(1, 2)) {
                if (border and (1 shl dir) == 0) continue
                val nx = x + (if (dir == 1) 1 else 0)
                val ny = y + (if (dir == 2) 1 else 0)
                if (nx >= w || ny >= h) continue
                out.append("F$x,$y,${1 shl dir}F$nx,$ny,${1 shl (dir xor 2)}")
            }
        }
        return out.toString()
    }

    private fun withMoves(save: String, moves: List<String>, statePos: Int = moves.size + 1): String {
        val parsed = SgtpSave.parse(save)
        val records = ArrayList<SaveRecord>()
        for (record in parsed.records) {
            if (record.key in setOf("NSTATES", "STATEPOS", "MOVE", "SOLVE", "RESTART")) continue
            records += record
        }
        records += SaveRecord("NSTATES", (1 + moves.size).toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private fun withoutRecord(save: String, key: String): String =
        SgtpSave.of(SgtpSave.parse(save).records.filter { it.key != key }).serialise()

    private fun replaceRecord(save: String, key: String, value: String): String =
        SgtpSave.of(
            SgtpSave.parse(save).records.map {
                if (it.key == key) SaveRecord(key, value) else it
            },
        ).serialise()

    private companion object {
        const val SEED = "12345"
    }
}
