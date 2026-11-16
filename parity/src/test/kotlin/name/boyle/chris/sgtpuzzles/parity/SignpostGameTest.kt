package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Game: signpost.
 *
 * The loaded state, the text form, the status, the move grammar through
 * the save replay path, arrow chaining, undo and redo, completion, the
 * two-state solve, the two solver errors, and the C text-format column
 * quirk. A `w < h` text form is Kotlin-only: C reads past its arrays
 * there, so the oracle is not asked for it.
 */
class SignpostGameTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 48
        const val DESC = "1dcegcefebeghcbh16a"
        const val SOLVE = "S1d9c11e10g13c4e14f7e3b5e2g8h15c6b12h16a"
    }

    private fun saveText(
        params: String = "4x4",
        cparams: String = "4x4c",
        desc: String = DESC,
        moves: List<String> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Signpost.name
        records += "PARAMS" to params
        records += "CPARAMS" to cparams
        records += "SEED" to SEED
        records += "DESC" to desc
        records += "NSTATES" to (moves.size + 1).toString()
        records += "STATEPOS" to (moves.size + 1).toString()
        for (move in moves) records += "MOVE" to move
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun loads(save: String): Boolean = try {
        Midend.fromSave(Signpost, SgtpSave.parse(save))
        true
    } catch (e: IllegalStateException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    @Test
    fun `a new game matches the oracle for every preset`() = OracleClient().use { oracle ->
        for (params in Signpost.presets().map { it.params }) {
            val midend = Midend.newGame(Signpost, params, SEED)
            val save = midend.serialise().serialise()
            val loaded = oracle.id(save)
            assertEquals("$params id", loaded.id, midend.id())
            assertEquals("$params text", oracle.text(save), midend.textFormat())
            assertEquals("$params status", loaded.status, midend.status())
            assertEquals("$params status 0", 0, midend.status())
            assertEquals("$params status text", "", midend.statusText())
            assertFalse("$params canUndo", midend.canUndo)
            assertFalse("$params canRedo", midend.canRedo)
            assertNull("$params cursor", midend.cursorLocation(TILE))

            val state = midend.state as Signpost.State
            val n = state.n
            for (i in 0 until n) {
                assertTrue("$params dir $i", state.dirs[i] in 0..7)
            }
            // Only the immutable description numbers are set. A chain
            // that check_completion auto-linked may add numbers, so the
            // text form is the exact comparison.
            assertEquals("$params text form", oracle.text(save), midend.textFormat())
        }
    }

    @Test
    fun `the move grammar through a save replay matches C`() = OracleClient().use { oracle ->
        val cases = listOf(
            listOf("L0,0-1,1"),
            listOf("L+0,+0-01,01"),
            listOf("L0,0-1,1junk"),
            listOf("L0,0-0,1"),
            listOf("L0,0-0,0"),
            listOf(" L0,0-1,1"),
            listOf("L0,0-9,9"),
            listOf("C0,0"),
            listOf("L0,0-1,1", "C0,0"),
            listOf("L1,0-2,0"),
            listOf("L1,0-2,0", "X1,0"),
            listOf("L1,0-2,0", "C1,0"),
            listOf("L1,0-2,0", "L2,0-2,1", "C1,0"),
            listOf("L1,0-2,0", "L2,0-2,1", "X1,0"),
            listOf("L1,0-2,0", "L2,0-2,1", "C2,0"),
            listOf("H"),
            listOf("h"),
            listOf("Z1,2"),
            listOf(""),
        )
        for (moves in cases) {
            val save = saveText(moves = moves)
            val oracleLoads = try {
                oracle.id(save)
                true
            } catch (e: OracleException) {
                false
            }
            assertEquals("'$moves' load parity", oracleLoads, loads(save))
            if (oracleLoads) {
                val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
                assertEquals("'$moves' canonical text", save, midend.serialise().serialise())
                if (moves != listOf("H")) {
                    assertEquals("'$moves' text", oracle.text(save), midend.textFormat())
                }
                assertEquals("'$moves' status", oracle.id(save).status, midend.status())
            }
        }

        // The cut of a whole colour set removes every cell of the set;
        // the single-cell cut leaves the rest of the chain.
        val whole = Midend.fromSave(
            Signpost,
            SgtpSave.parse(
                saveText(moves = listOf("L1,0-2,0", "L2,0-2,1", "X1,0")),
            ),
        )
        val single = Midend.fromSave(
            Signpost,
            SgtpSave.parse(
                saveText(moves = listOf("L1,0-2,0", "L2,0-2,1", "C1,0")),
            ),
        )
        val wholeState = whole.state as Signpost.State
        val singleState = single.state as Signpost.State
        assertTrue("a whole-set cut leaves no link", wholeState.next.all { it == -1 })
        assertTrue("a single cut leaves a link", singleState.next.any { it != -1 })
    }

    @Test
    fun `chaining numbers and colours follows update_numbers`() = OracleClient().use { oracle ->
        // Link 1 to (1,1): the immutable 1 stays the chain head, the new
        // cell becomes 2, and the pair is a real-number chain (set 0).
        val one = Midend.fromSave(
            Signpost,
            SgtpSave.parse(saveText(moves = listOf("L0,0-1,1"))),
        )
        val oneState = one.state as Signpost.State
        assertEquals(1, oneState.nums[0])
        assertEquals(2, oneState.nums[1 * 4 + 1])
        assertEquals(1 * 4 + 1, oneState.numsi[2])
        assertEquals("set 0", 0, oneState.nums[1 * 4 + 1] / (oneState.n + 1))

        // Two blank cells form a coloured set: the text shows the base-26
        // set letter and numbers from 0.
        val colour = Midend.fromSave(
            Signpost,
            SgtpSave.parse(saveText(moves = listOf("L1,0-2,0"))),
        )
        val colourState = colour.state as Signpost.State
        assertEquals(1, colourState.nums[1] / (colourState.n + 1))
        assertEquals(0, colourState.nums[1] % (colourState.n + 1))
        assertEquals(1, colourState.nums[2] % (colourState.n + 1))
        assertEquals(
            "a coloured set is drawn with its letter prefix",
            true,
            colour.textFormat().contains("a 0"),
        )
        assertTrue(colour.textFormat().contains("a 1"))

        val state = one.state
        assertNotNull(Signpost.executeMove(state, "L1,0-2,0"))

        // A closed chain is refused: after 0,0 -> 1,0 -> 1,1 -> 0,1 the
        // last arrow would link back into the same DSF class.
        val loop = Signpost.newState(
            Signpost.Params(2, 2, false),
            "ceag",
        )
        val first = requireNotNull(Signpost.executeMove(loop, "L0,0-1,0"))
        val second = requireNotNull(Signpost.executeMove(first, "L1,0-1,1"))
        val third = requireNotNull(Signpost.executeMove(second, "L0,1-0,0"))
        assertNull(Signpost.executeMove(third, "L0,1-0,0"))

        // Every accepted move is the same as C through a save replay.
        val save = saveText(moves = listOf("L0,0-1,1"))
        assertEquals(oracle.text(save), one.textFormat())
    }

    @Test
    fun `the numsi n clear bound matches C`() = OracleClient().use { oracle ->
        // The only immutable number is 3. The link numbers the second cell
        // 4, the cut removes it, and the clear loop stops at n-1, so
        // numsi[4] stays stale. check_completion reads it and links 3 to
        // the stale cell again.
        val desc = "3ceaa"
        val cut = saveText(
            params = "2x2",
            cparams = "2x2",
            desc = desc,
            moves = listOf("L0,0-1,0", "C1,0"),
        )
        val cutState = Midend.fromSave(Signpost, SgtpSave.parse(cut)).state as Signpost.State
        assertEquals(1, cutState.numsi[4])
        assertEquals(1, cutState.next[0])
        assertEquals(0, cutState.prev[1])
        assertEquals(0, cutState.nums[1])
        assertTrue("the reused cell is marked", cutState.isError(1, 0))

        // One more move renumbers the stale cell. The text is the exact C
        // comparison: a clear that also covered n would leave it blank.
        val moved = saveText(
            params = "2x2",
            cparams = "2x2",
            desc = desc,
            moves = listOf("L0,0-1,0", "C1,0", "L0,1-0,0"),
        )
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(moved))
        assertEquals(oracle.text(moved), midend.textFormat())
        val movedState = midend.state as Signpost.State
        assertEquals(4, movedState.nums[1])
        assertEquals(1, movedState.numsi[4])
    }

    @Test
    fun `undo and redo match the oracle`() = OracleClient().use { oracle ->
        val save = saveText(moves = listOf("L0,0-1,1"))
        val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
        assertEquals(save, midend.serialise().serialise())

        assertTrue(midend.undo())
        assertEquals(oracle.undo(save).state, midend.serialise().serialise())
        assertTrue(midend.canRedo)
        assertTrue(midend.redo())
        assertEquals(oracle.redo(oracle.undo(save).state).state, midend.serialise().serialise())
        assertEquals(save, midend.serialise().serialise())
    }

    @Test
    fun `a solve completes the game and the flag is sticky`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Signpost, "4x4c", SEED)
        assertTrue(midend.solve())
        val text = midend.serialise().serialise()
        assertEquals(listOf(SOLVE), midend.serialise().values("SOLVE"))
        assertEquals(1, midend.status())
        assertTrue((midend.state as Signpost.State).completed)
        assertTrue((midend.state as Signpost.State).usedSolve)
        assertEquals(oracle.solve(oracle.newGame("signpost", "4x4c", SEED).state).state, text)
        assertEquals(oracle.text(text), midend.textFormat())
        assertEquals(1, oracle.status(text).status)

        // A further accepted move starts from the completed state, and
        // the sticky flag is kept. All cells are linked, so an L is
        // rejected; a valid cut is accepted and keeps the status.
        assertNull(Signpost.executeMove(midend.state, "L0,0-1,1"))
        val cut = requireNotNull(Signpost.executeMove(midend.state, "C0,0"))
        assertEquals(1, Signpost.status(cut))
        assertTrue((cut as Signpost.State).completed)
    }

    @Test
    fun `the solve tries the current state and then the initial state`() =
        OracleClient().use { oracle ->
            // The link L0,0-1,1 makes the current board impossible, so C
            // returns the initial board's solution.
            val save = saveText(moves = listOf("L0,0-1,1"))
            val expected = oracle.solve(save)
            assertTrue(expected.state.contains("SOLVE"))
            assertEquals(
                SOLVE,
                SgtpSave.parse(expected.state).value("SOLVE"),
            )

            val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
            assertTrue(midend.solve())
            assertEquals(expected.state, midend.serialise().serialise())
            assertEquals(1, midend.status())
            assertEquals(SOLVE, midend.serialise().value("SOLVE"))

            // The helper reports the same move for a direct caller.
            val current = midend.state
            val attempt = Signpost.solveAttempt(
                Midend.fromSave(Signpost, SgtpSave.parse(save)).state,
                null,
            )
            assertEquals(SOLVE, attempt.move)
            assertNull(attempt.error)
            // The current state is already the solved one here.
            assertEquals(1, Signpost.status(current))
        }

    @Test
    fun `the two solver error strings match C`() = OracleClient().use { oracle ->
        for ((params, desc, message) in listOf(
            Triple("2x2", "aaaa", "Puzzle is impossible."),
            Triple("4x1", "ccgg", "Unable to solve puzzle."),
        )) {
            val save = saveText(
                params = params,
                cparams = params,
                desc = desc,
            )
            val expected = try {
                oracle.solve(save)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("$params:$desc C error", message, expected)

            val midend = Midend.fromSave(Signpost, SgtpSave.parse(save))
            val attempt = Signpost.solveAttempt(midend.state, null)
            assertNull("$params:$desc move", attempt.move)
            assertEquals("$params:$desc port error", message, attempt.error)
            assertFalse("$params:$desc midend solve", midend.solve())
        }
    }

    @Test
    fun `the text form keeps the C column bound`() = OracleClient().use { oracle ->
        // `w > h`: C loops over the first `h` columns, so the last
        // columns of every row are dropped. The port reproduces it.
        val wide = Midend.newGame(Signpost, "5x4", SEED)
        assertEquals(oracle.text(wide.serialise().serialise()), wide.textFormat())
        val wideLines = wide.textFormat().lines()
        // Four groups of four characters per row, then a newline.
        assertEquals(16, wideLines[0].length)
        assertEquals(16, wideLines[1].length)
        assertEquals(4, wideLines.first().chunked(4).count { it.isNotEmpty() })

        // `w < h`: C reads past `dirs`, `nums`, and `flags`. The port
        // defines an out-of-range cell as N, 0, and no flags, and keeps
        // the same `h` column bound. The oracle is not asked.
        val tall = Midend.newGame(Signpost, "4x5", SEED)
        val text = tall.textFormat()
        assertEquals(5, text.split("\n\n").size - 1)
        assertEquals(20, text.lines().first().length)

        // 99 cells is the last size that formats. `11x9` keeps the C `h`
        // column bound deterministic, so the oracle is asked.
        val desc99 = "a".repeat(99)
        val almost = saveText(params = "11x9", cparams = "11x9", desc = desc99)
        val almostText = Midend.fromSave(Signpost, SgtpSave.parse(almost)).textFormat()
        assertTrue("99 cells still format", almostText.isNotEmpty())
        assertEquals(oracle.text(almost), almostText)
        // `9x11` is taller than wide: C reads past its arrays there, so the
        // oracle is not asked and the engine test pins the definition.
        val tall99 = Midend.fromSave(
            Signpost,
            SgtpSave.parse(saveText(params = "9x11", cparams = "9x11", desc = desc99)),
        )
        val tallState = tall99.state as Signpost.State
        assertEquals(99, tallState.n)
        assertEquals(11, tall99.textFormat().split("\n\n").size - 1)

        // At 100 cells, `can_format_as_text_now` is false and the seam
        // has no hook, so the method returns the empty string. The
        // oracle prints NULL as the empty string as well.
        val desc = "a".repeat(100)
        val big = saveText(params = "10x10", cparams = "10x10", desc = desc)
        assertEquals("", oracle.text(big))
        val bigMidend = Midend.fromSave(Signpost, SgtpSave.parse(big))
        assertEquals("", bigMidend.textFormat())
    }

    @Test
    fun `requestKeys matches the oracle`() = OracleClient().use { oracle ->
        val state = oracle.newGame("signpost", "4x4c", SEED).state
        val expected = oracle.requestKeys(state)
        val actual = Signpost.requestKeys(Signpost.defaultParams())
        assertEquals(3, expected.arrowMode)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, actual.arrowMode)
        assertEquals(1, expected.labels.size)
        assertEquals(1, actual.labels.size)
        assertEquals(expected.labels[0].label, actual.labels[0].name)
        assertEquals(expected.labels[0].button, actual.labels[0].button)
        assertEquals(expected.labels[0].needsArrows, actual.labels[0].needsArrows)
        assertEquals("Clear", actual.labels[0].name)
        assertEquals('\b'.code, actual.labels[0].button)

        // `canSolve` and `wantsStatusBar` are the C flags.
        assertTrue(Signpost.canSolve)
        assertFalse(Signpost.wantsStatusBar)
        assertTrue(Signpost.canFormatAsText)
        assertEquals(48, Signpost.preferredTileSize)
    }
}
