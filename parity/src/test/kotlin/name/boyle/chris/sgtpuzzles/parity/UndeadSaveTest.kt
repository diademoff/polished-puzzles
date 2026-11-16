package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Undead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: undead.
 *
 * Every corpus save is read by the Kotlin midend, rewritten byte for
 * byte, and reloaded by C. A Kotlin save for every preset is loaded by C
 * with the same id and text. The history cases cover a played cell, a
 * pencil mark, a clear, a clue, `M`, a `SOLVE`, a `RESTART`, an undone
 * position with a live redo branch, and an empty `MOVE`.
 *
 * The `PARAMS`/`CPARAMS` split is part of the save format: the short
 * encoding drops the difficulty, so a generated Easy or Tricky save has
 * the Normal long form in `PARAMS` and the real difficulty in `CPARAMS`.
 */
class UndeadSaveTest {

    private companion object {
        const val SEED = "12345"

        const val DEFAULT_DESC =
            "1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"

        const val DEFAULT_SOLVE =
            "S;Z0;Z1;Z2;Z3;Z4;G5;V6;V7;V8;V9;Z10;V11"

        const val FIXED_DESC =
            "1,5,6,ZZRaaaGRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun saveText(
        params: String = "4x4dn",
        desc: String = DEFAULT_DESC,
        records: List<Pair<String, String>> = emptyList(),
        statePos: Int = records.count { it.first != "AUXINFO" } + 1,
    ): String {
        val out = StringBuilder()
        fun record(key: String, value: String) {
            out.append(key.padEnd(8)).append(':').append(value.length).append(':')
                .append(value).append('\n')
        }
        record("SAVEFILE", "Simon Tatham's Portable Puzzle Collection")
        record("VERSION", "1")
        record("GAME", "Undead")
        record("PARAMS", "4x4dn".let {
            when {
                params.startsWith("5x5") -> "5x5dn"
                params.startsWith("7x7") -> "7x7dn"
                else -> it
            }
        })
        record("CPARAMS", params)
        record("SEED", SEED)
        record("DESC", desc)
        for ((key, value) in records) {
            if (key == "AUXINFO") record(key, value)
        }
        record("NSTATES", (records.count { it.first != "AUXINFO" } + 1).toString())
        record("STATEPOS", statePos.toString())
        for ((key, value) in records) {
            if (key != "AUXINFO") record(key, value)
        }
        return out.toString()
    }

    @Test
    fun `every undead save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/undead")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(9, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Undead, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertNull("${file.name} has no AUXINFO", midend.serialise().value("AUXINFO"))
                assertFalse(
                    "${file.name} has no UI record",
                    midend.serialise().records.any { it.key == "UI" },
                )

                // The split is the generated form.
                val save = SgtpSave.parse(text)
                val cp = requireNotNull(save.value("CPARAMS"))
                assertEquals(
                    "${file.name} PARAMS is the Normal long form",
                    "${cp.substringBefore('d')}dn",
                    save.value("PARAMS"),
                )
            }

            // `default.sgtp` and `4x4dn.sgtp` are the same file.
            assertEquals(
                File(testdata, "saves/initial/undead/4x4dn.sgtp").readText(),
                File(testdata, "saves/initial/undead/default.sgtp").readText(),
            )
        }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Undead.presets().map { it.params }) {
                val midend = Midend.newGame(Undead, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertEquals("$params status", midend.status(), loaded.status)
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertNull("$params has no AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
                assertFalse("$params has no UI", SgtpSave.parse(text).records.any { it.key == "UI" })

                // The split: the id and the PARAMS record drop the real
                // difficulty; CPARAMS keeps it.
                val save = SgtpSave.parse(text)
                assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
                assertEquals(
                    "$params PARAMS",
                    "${params.substringBefore('d')}dn",
                    save.value("PARAMS"),
                )
                assertEquals(params.substringBefore('d'), midend.id().substringBefore(':'))
            }
        }

    @Test
    fun `a generated save is byte-identical to the oracle`() = OracleClient().use { oracle ->
        val seeds = listOf("1", "2", "3", "12345", "99999", "seed", "another seed", "", "-7")
        for (params in Undead.presets().map { it.params }) {
            for (seed in seeds) {
                val expected = oracle.newGame("undead", params, seed)
                val actual = Midend.newGame(Undead, params, seed)
                assertEquals("'$params' seed '$seed' id", expected.id, actual.id())
                assertEquals(
                    "'$params' seed '$seed' save",
                    expected.state,
                    actual.serialise().serialise(),
                )
            }
        }
    }

    @Test
    fun `the move records round-trip in both directions`() = OracleClient().use { oracle ->
        val cases = listOf(
            listOf("MOVE" to "G0"),
            listOf("MOVE" to "g0"),
            listOf("MOVE" to "E0"),
            listOf("MOVE" to "D1,0"),
            listOf("MOVE" to "M"),
            listOf("MOVE" to "G0", "MOVE" to "V1", "MOVE" to "Z2"),
            listOf("MOVE" to "G0V1"),
            listOf("MOVE" to ""),
            listOf("SOLVE" to DEFAULT_SOLVE),
            listOf("RESTART" to DEFAULT_DESC),
            listOf("MOVE" to "S;G0"),
            listOf("MOVE" to "S"),
            listOf("MOVE" to "D1,0", "MOVE" to "G0", "RESTART" to DEFAULT_DESC),
        )
        for (records in cases) {
            val text = saveText(records = records)
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Undead, SgtpSave.parse(text))
            assertSame("$records", expected, midend)
            assertEquals("$records text", oracle.text(text), midend.textFormat())
            assertEquals(
                "$records canonical",
                text,
                midend.serialise().serialise(),
            )
        }

        // An undone position with a live redo branch.
        val undone = saveText(
            records = listOf("MOVE" to "G0", "MOVE" to "V1", "MOVE" to "Z2"),
            statePos = 2,
        )
        val expected = oracle.id(undone)
        val midend = Midend.fromSave(Undead, SgtpSave.parse(undone))
        assertSame("undone", expected, midend)
        assertTrue("undone can undo", midend.canUndo)
        assertTrue("undone can redo", midend.canRedo)
        val redo = oracle.redo(undone)
        assertTrue(midend.redo())
        assertSame("redo", redo, midend)
        assertEquals(redo.state, midend.serialise().serialise())
    }

    @Test
    fun `a solver move is cheated in both directions`() = OracleClient().use { oracle ->
        for (text in listOf(
            saveText(records = listOf("MOVE" to "S;G0")),
            saveText(records = listOf("MOVE" to "S")),
            saveText(records = listOf("SOLVE" to DEFAULT_SOLVE)),
        )) {
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Undead, SgtpSave.parse(text))
            assertSame("cheated", expected, midend)
            val state = midend.state as Undead.State
            assertTrue("the S token sets cheated", state.cheated)
        }
    }

    @Test
    fun `a hand-written fixed board loads and solves in both directions`() =
        OracleClient().use { oracle ->
            val text = saveText(desc = FIXED_DESC)
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Undead, SgtpSave.parse(text))
            assertSame("fixed board", expected, midend)
            val state = midend.state as Undead.State
            assertTrue(state.common.fixed[0])
            assertTrue(state.common.fixed[1])
            assertTrue(state.common.fixed[5])
            assertEquals(4, state.guess[0])
            assertEquals(4, state.guess[1])
            assertEquals(1, state.guess[5])

            // A click on a fixed cell is inert.
            val before = midend.serialise().serialise()
            midend.processKey(
                64 / 4 + 64 + 64 / 2,
                64 / 4 + 64 + 3 * 64 / 2,
                name.boyle.chris.sgtpuzzles.engine.Buttons.LEFT_BUTTON,
                64,
            )
            assertEquals("no click happened", before, midend.serialise().serialise())

            // The oracle accepts the same fixed board and the solver runs.
            val solved = oracle.solve(text)
            val actual = Midend.fromSave(Undead, SgtpSave.parse(text))
            assertTrue(actual.solve())
            assertSame("fixed solve", solved, actual)
        }

    @Test
    fun `a hand-written AUXINFO is ignored and stays in the save`() =
        OracleClient().use { oracle ->
            val records = listOf("AUXINFO" to "00")
            val text = saveText(records = records)
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Undead, SgtpSave.parse(text))
            assertSame("aux", expected, midend)
            assertEquals("aux kept", "00", midend.serialise().value("AUXINFO"))
            assertEquals(DEFAULT_SOLVE, Undead.solve(midend.state, midend.auxInfo))
        }

    @Test
    fun `an empty seed is a zero-length SEED record`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Undead, "4x4dn", "")
        val save = midend.serialise()
        assertEquals("", save.value("SEED"))
        assertNull(save.value("HEXSEED"))
        assertEquals(save.serialise(), oracle.id(save.serialise()).state)
    }
}
