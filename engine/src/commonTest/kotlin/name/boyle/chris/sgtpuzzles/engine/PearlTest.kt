package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: pearl.
 *
 * Kotlin-only pins for the rules that the oracle protocol cannot express,
 * plus the fixed vectors behind the parity suite: the parameter quirks,
 * the validation order, the description encoder, the solver, the move
 * parser, the two C undefined-behavior definitions, the appearance
 * preference, and the drawing geometry.
 */
class PearlTest {

    private companion object {
        const val SEED = "12345"
    }

    private fun params(text: String): Pearl.Params =
        Pearl.decodeParams(text) as Pearl.Params

    private fun state(desc: String, paramsText: String = "8x8de"): Pearl.State =
        Pearl.newState(params(paramsText), desc) as Pearl.State

    private fun desc(paramsText: String, seed: String): String =
        Pearl.newDesc(params(paramsText), RandomState.fromSeed(seed)).text

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `identity and presets match the C game`() {
        assertEquals("Pearl", Pearl.name)
        assertEquals("pearl", Pearl.backendName)
        assertEquals(31, Pearl.preferredTileSize)
        assertTrue(Pearl.canSolve)
        assertTrue(Pearl.canFormatAsText)
        assertFalse(Pearl.wantsStatusBar)
        assertFalse(Pearl.isTimed)
        assertEquals(0, Pearl.flags)

        assertEquals(Pearl.Params(8, 8, 1, false), Pearl.defaultParams())
        val presets = Pearl.presets()
        assertEquals(8, presets.size)
        assertEquals(
            listOf(
                "6x6 Easy" to "6x6de",
                "6x6 Tricky" to "6x6dt",
                "8x8 Easy" to "8x8de",
                "8x8 Tricky" to "8x8dt",
                "10x10 Easy" to "10x10de",
                "10x10 Tricky" to "10x10dt",
                "12x8 Easy" to "12x8de",
                "12x8 Tricky" to "12x8dt",
            ),
            presets.map { it.title to it.params },
        )
        assertEquals(8, presets.map { it.params }.distinct().size)
    }

    @Test
    fun `decode quirks follow the C control flow`() {
        fun p(text: String) = params(text)

        assertEquals(Pearl.Params(8, 8, 0, false), p("8x8"))
        assertEquals(Pearl.Params(8, 8, 1, false), p("8x8dt"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("8x8d"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("8x8dq"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("8x8det"))
        assertEquals(Pearl.Params(8, 8, 1, false), p("8x8dtx"))
        assertEquals(Pearl.Params(8, 8, 1, true), p("8x8dtn"))
        assertEquals(Pearl.Params(8, 8, 0, true), p("8x8den"))
        // The `n` occupied the difficulty slot, so the nosolve test fails.
        assertEquals(Pearl.Params(8, 8, 0, false), p("8x8dn"))
        assertEquals(Pearl.Params(8, 8, 0, true), p("8x8n"))
        assertEquals(Pearl.Params(8, 8, 0, true), p("8x8nn"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("8X8"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("08x008"))
        // `atoi` accepts the sign or space, then the digit scan stops on it.
        assertEquals(Pearl.Params(8, 8, 0, false), p("+8x8"))
        assertEquals(Pearl.Params(8, 8, 0, false), p(" 8x8"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("8x+8"))
        assertEquals(Pearl.Params(8, 8, 0, false), p("8x8\tde"))

        // The short encoding drops the difficulty and nosolve.
        assertEquals("8x8", Pearl.encodeParams(p("8x8dt"), false))
        assertEquals("8x8dt", Pearl.encodeParams(p("8x8dt"), true))
        assertEquals("8x8", Pearl.encodeParams(p("8x8dt"), false))
        assertEquals("8x8den", Pearl.encodeParams(p("8x8den"), true))
        assertEquals("8x8", Pearl.encodeParams(p("8x8den"), false))
    }

    @Test
    fun `validation messages are exact and ordered`() {
        fun message(text: String, full: Boolean = true): String? =
            Pearl.validateParams(params(text), full)

        assertEquals("Width must be at least five", message("4x8"))
        assertEquals("Height must be at least five", message("8x4"))
        assertEquals(
            "Width times height must not be unreasonably large",
            message("46341x46341"),
        )
        assertEquals(
            "Width or height must be at least six for Tricky",
            message("5x5dt"),
        )
        assertNull(message("6x5dt"))
        assertNull(message("5x6dt"))
        assertNull(message("5x5de"))
        // A difficulty outside the enum is reachable only by a direct
        // value, not by decode_params.
        assertEquals(
            "Unknown difficulty level",
            Pearl.validateParams(Pearl.Params(8, 8, 2, false), true),
        )
        assertEquals(
            "Unknown difficulty level",
            Pearl.validateParams(Pearl.Params(8, 8, -1, false), true),
        )
        // `full` is ignored.
        assertNull(Pearl.validateParams(Pearl.Params(8, 8, 1, true), false))
    }

    @Test
    fun `validate desc messages and the run boundary`() {
        val p = params("8x8de")
        assertEquals(
            "unrecognised character in string",
            Pearl.validateDesc(p, "A"),
        )
        assertEquals(
            "unrecognised character in string",
            Pearl.validateDesc(p, "1"),
        )
        assertEquals(
            "unrecognised character in string",
            Pearl.validateDesc(p, "-"),
        )
        assertEquals(
            "unrecognised character in string",
            Pearl.validateDesc(p, " "),
        )
        assertEquals("string too long", Pearl.validateDesc(params("5x5de"), "z"))
        assertEquals("string too short", Pearl.validateDesc(params("5x5de"), "x"))
        // The invalid-character scan runs over the whole string before
        // the length checks, as C does.
        assertEquals(
            "unrecognised character in string",
            Pearl.validateDesc(params("5x5de"), "zzA"),
        )
        assertEquals("string too long", Pearl.validateDesc(params("5x5de"), "zz"))
        // A run stops at `z` (26) and a 27th blank starts a new run.
        val total26 = Pearl.Params(13, 2, 0, false)
        val total27 = Pearl.Params(9, 3, 0, false)
        assertNull(Pearl.validateDesc(total26, "z"))
        assertEquals("string too long", Pearl.validateDesc(total26, "za"))
        assertEquals("string too short", Pearl.validateDesc(total27, "z"))
        assertNull(Pearl.validateDesc(total27, "za"))
        assertEquals("za", Pearl.encodeDesc(9, 3, IntArray(27)))
    }

    // ------------------------------------------------------------------
    // Description and generation.
    // ------------------------------------------------------------------

    @Test
    fun `the nine golden descriptions match`() {
        val goldens = listOf(
            "" to "8x8:dWaWcWbWWaBhWbWWdBBaWiWeWbBbWd",
            "10x10de" to "10x10:aWaWBaBbBkWWaBaBaWWaWdBgBbBaBWiWBbBaBWWnWWbWWcBbBbWa",
            "10x10dt" to "10x10:eWaBjWaWWaBaWcWcBbWcWcBaWlWBaBWbWBaBcBbBmBcWbWaB",
            "12x8de" to "12x8:BfWjWbWeBBaWaBbBWcWaWgWaWcBbWbWaWaWBaWnBbWcBaWaB",
            "12x8dt" to "12x8:eBbBbBaWbBaWeBrBbBbWWbWWlWaWdBBbWWaWkWaB",
            "6x6de" to "6x6:BaWnBBdBaWfWc",
            "6x6dt" to "6x6:cWaBWhWdWWbWgBbB",
            "8x8de" to "8x8:dWaWcWbWWaBhWbWWdBBaWiWeWbBbWd",
            "8x8dt" to "8x8:aBdWfWcWeBbBdWbBbWaWcBlBbBaWWa",
        )
        for ((paramsText, id) in goldens) {
            assertEquals(id, Midend.newGame(Pearl, paramsText, SEED).id(), paramsText)
        }
        // The empty parameter entry pins the short-encoder default: it is
        // the same game as `8x8de`, not the Tricky default preset.
        assertEquals(
            Midend.newGame(Pearl, "8x8de", SEED).id(),
            Midend.newGame(Pearl, "", SEED).id(),
        )
    }

    @Test
    fun `seed vectors match the C generator`() {
        val vectors = listOf(
            Triple("6x6de", "1", "6x6:BhBhBaBaWWfBdB"),
            Triple("6x6de", "2", "6x6:BbBWiWBWeWgBaBBb"),
            Triple("6x6de", "3", "6x6:BaBbBfWaBeBWjBbB"),
            Triple("6x6de", "seed", "6x6:BhBcWWfBaBbBdWWc"),
            Triple("6x6de", "another seed", "6x6:BbBjWBWaBaWdWWhB"),
            Triple("6x6de", "0", "6x6:cWaBfBbWbBbBaBbWhB"),
            Triple("6x6de", "-7", "6x6:cWaBcWcBcWbWBhBbBb"),
            Triple("6x6de", "", "6x6:dWbWaBdBfWaWgBcB"),
            Triple("6x6dt", "1", "6x6:cWaBgBaWaWaBmWWa"),
            Triple("6x6dt", "2", "6x6:eBbBfWbWbWbWhWaB"),
            Triple("6x6dt", "3", "6x6:hBBhWbWaWWhWaB"),
            Triple("6x6dt", "seed", "6x6:cWaBWeWbWeBaBbBdWWc"),
            Triple("6x6dt", "another seed", "6x6:aWWeBhBWdBWhWaB"),
            Triple("6x6dt", "0", "6x6:BgWcWaWaBeBhWWc"),
            Triple("6x6dt", "-7", "6x6:dWbWbWaBcWiWcBbWb"),
            Triple("6x6dt", "", "6x6:cWdBfWbWaWbWeWBaWc"),
        )
        for ((paramsText, seed, id) in vectors) {
            assertEquals(id, Midend.newGame(Pearl, paramsText, seed).id(), "$paramsText $seed")
        }
    }

    @Test
    fun `the auxiliary text is the loop and the clue array accepts it`() {
        for (preset in Pearl.presets()) {
            val p = params(preset.params)
            val generated = Pearl.generateClues(p, RandomState.fromSeed(SEED))
            val aux = Pearl.encodeAux(p.w * p.h, generated.grid)
            assertEquals(p.w * p.h, aux.length, preset.params)
            assertTrue(aux.all { it in '0'..'9' || it in 'A'..'F' }, preset.params)

            val decoded = IntArray(p.w * p.h) {
                val c = aux[it]
                if (c in '0'..'9') c - '0' else c - 'A' + 10
            }
            // Every cell has degree 0 or 2.
            for (y in 0 until p.h) {
                for (x in 0 until p.w) {
                    val degree = decoded[y * p.w + x].countOneBits()
                    assertTrue(degree == 0 || degree == 2, "${preset.params} at $x,$y")
                }
            }
            // The clues and the loop complete the board.
            val desc = Pearl.encodeDesc(p.w, p.h, generated.clues)
            val built = Pearl.newState(p, desc) as Pearl.State
            decoded.copyInto(built.lines)
            assertTrue(Pearl.checkCompletion(built, true), preset.params)
            assertTrue(built.completed, preset.params)
        }
    }

    @Test
    fun `generation reports retries and the retry fixtures stay deterministic`() {
        // These seeds reject at least one full-clue board, so they exercise
        // the retry loop and the RNG state after a rejection.
        val retries = listOf(
            Triple("6x6de", "0", 3),
            Triple("6x6de", "1", 29),
            Triple("6x6dt", "6", 98),
        )
        for ((paramsText, seed, minimum) in retries) {
            val p = params(paramsText)
            val (description, attempts) = Pearl.generate(p, RandomState.fromSeed(seed))
            assertTrue(attempts >= minimum, "$paramsText $seed attempts $attempts")
            assertEquals(
                Midend.newGame(Pearl, paramsText, seed).id(),
                Pearl.encodeParams(p, false) + ":" + description.text,
            )
            // The same seed gives the same answer.
            val again = Pearl.generate(p, RandomState.fromSeed(seed))
            assertEquals(description, again.first)
            assertEquals(attempts, again.second)
        }
    }

    // ------------------------------------------------------------------
    // The two C undefined-behavior definitions.
    // ------------------------------------------------------------------

    @Test
    fun `the clue removal list repeats the straights and the buffer covers it`() {
        val p = params("5x5de")
        val allStraights = IntArray(p.w * p.h) { 2 }
        val removal = Pearl.buildClueRemovalOrder(
            p.w,
            p.h,
            allStraights,
            RandomState.fromSeed("1"),
        )
        assertEquals(25, removal.straightCount)
        assertEquals(25, removal.cornerCount)
        assertEquals(25, removal.cornerBase)
        assertEquals(50, removal.capacity)
        // C allocates w*h == 25 ints and writes to index 49 here.
        assertTrue(removal.cornerBase + removal.cornerCount > p.w * p.h)
        assertTrue(removal.cornerBase + removal.cornerCount <= removal.capacity)
        assertEquals(50, removal.positions.size)
        // The removal loop reads `straights` for both counters, so the
        // second half repeats the first half in the same order.
        for (i in 0 until 25) {
            assertEquals(removal.positions[i], removal.positions[25 + i], "position $i")
        }
    }

    @Test
    fun `a drag that visits every cell keeps the extra entry and stays defined`() {
        val p = params("5x5de")
        val midend = Midend.fromGameId(Pearl, "5x5:y")
        val ui = midend.ui as Pearl.Ui
        assertEquals(26, ui.dragcoords.size, "one entry more than the cell count")

        val tile = 31
        val border = tile / 2
        fun px(x: Int) = border + x * tile + tile / 2
        fun py(y: Int) = border + y * tile + tile / 2

        // A serpentine path visits every cell: row 0 left to right, row 1
        // right to left, and so on.
        val path = ArrayList<Pair<Int, Int>>()
        for (y in 0 until p.h) {
            val xs = if (y % 2 == 0) 0 until p.w else (p.w - 1 downTo 0)
            for (x in xs) path.add(x to y)
        }
        assertEquals(25, path.size)

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(px(0), py(0), Buttons.LEFT_BUTTON, tile),
        )
        for ((x, y) in path.drop(1)) {
            midend.processKey(px(x), py(y), Buttons.LEFT_DRAG, tile)
        }
        assertEquals(25, ui.ndragcoords, "the path holds every cell")

        // Dragging into the start cell is the loop-drag case. C writes
        // `dragcoords[25]` one past its `sz`-entry array; the port keeps
        // the value and does not overflow.
        midend.processKey(px(0), py(0), Buttons.LEFT_DRAG, tile)
        assertEquals(25, ui.ndragcoords)
        assertEquals(0, ui.dragcoords[25], "the extra entry holds the start cell")

        // The last cell is not in the same row or column as the start, so
        // the closing edge is diagonal. The release therefore builds an
        // invalid move and the midend rejects it without a state.
        val outcome = midend.processKey(px(0), py(0), Buttons.LEFT_RELEASE, tile)
        assertEquals(KeyOutcome.SomeEffect, outcome)
        assertEquals(2, midend.stateCount)
        assertEquals(-1, ui.ndragcoords)
        // The release emits the 24 path edges and never reads the extra
        // closing entry; the result is a valid path, not a completed loop.
        val move = midend.serialise().value("MOVE")!!
        assertEquals(24, move.split(';').size / 2, move)
        assertEquals(0, midend.status())
    }

    // ------------------------------------------------------------------
    // Solver and solve.
    // ------------------------------------------------------------------

    @Test
    fun `the solver returns the measured ambiguous payloads`() {
        val blank = Midend.fromGameId(Pearl, "8x8:zzl")
        assertEquals(
            Pearl.SolveResult.Move("S"),
            Pearl.solveAttempt(blank.state),
        )
        val line = Pearl.executeMove(blank.state, "F1,0,0;F4,1,0") as Pearl.State
        assertEquals(
            Pearl.SolveResult.Move("S;R0,0,0;R0,1,0"),
            Pearl.solveAttempt(line),
        )
    }

    @Test
    fun `a solve error keeps the C message and Game solve returns null`() {
        val impossible = Midend.fromGameId(Pearl, "8x8:" + "B".repeat(64))
        assertEquals(
            Pearl.SolveResult.Error("Unable to find solution"),
            Pearl.solveAttempt(impossible.state),
        )
        assertNull(Pearl.solve(impossible.state))
    }

    @Test
    fun `the auxiliary solve path validates every character`() {
        val s = state("dWaWcWbWWaBhWbWWdBBaWiWeWbBbWd", "8x8de")
        assertEquals(
            Pearl.SolveResult.Move("S"),
            Pearl.solveAttempt(s, "0".repeat(64)),
        )
        assertEquals(
            Pearl.SolveResult.Error("invalid char in aux"),
            Pearl.solveAttempt(s, "G" + "0".repeat(63)),
        )
        // C reads the NUL terminator, which is not a hex digit.
        assertEquals(
            Pearl.SolveResult.Error("invalid char in aux"),
            Pearl.solveAttempt(s, "0"),
        )
        assertEquals(
            Pearl.SolveResult.Error("invalid char in aux"),
            Pearl.solveAttempt(s, ""),
        )
    }

    @Test
    fun `every preset solves and the move sets used solve`() {
        for (preset in Pearl.presets()) {
            val midend = Midend.newGame(Pearl, preset.params, SEED)
            val payload = Pearl.solve(midend.state)
            assertNotNull(payload, preset.params)
            assertTrue(payload.startsWith("S"), preset.params)
            assertTrue(midend.solve(), preset.params)
            assertEquals(1, midend.status(), preset.params)
            assertTrue((midend.state as Pearl.State).usedSolve, preset.params)
            assertEquals(payload, midend.serialise().value("SOLVE"), preset.params)
        }
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar applies the five tokens`() {
        val s = state("y", "5x5de")
        val edge = Pearl.executeMove(s, "F1,0,0;F4,1,0") as Pearl.State
        assertEquals(1, edge.lines[0])
        assertEquals(4, edge.lines[1])

        val marked = Pearl.executeMove(s, "M1,0,0;M4,1,0") as Pearl.State
        assertEquals(1, marked.marks[0])
        assertEquals(4, marked.marks[1])

        // L adds two edges of a path, and N removes the second one.
        val or = Pearl.executeMove(
            s,
            "L1,0,0;L4,1,0;L8,1,0;L2,1,1",
        ) as Pearl.State
        assertEquals(1, or.lines[0])
        assertEquals(12, or.lines[1])
        assertEquals(2, or.lines[1 * 5 + 1])
        val not = Pearl.executeMove(or, "N8,1,0;N2,1,1") as Pearl.State
        assertEquals(1, not.lines[0])
        assertEquals(4, not.lines[1])

        // R replaces the lines of a cell and clears the marks for them.
        val replace = Pearl.executeMove(marked, "R1,0,0;R4,1,0") as Pearl.State
        assertEquals(1, replace.lines[0])
        assertEquals(4, replace.lines[1])
        assertEquals(0, replace.marks[0] and 1)
        assertEquals(0, replace.marks[1] and 4)

        val flipped = Pearl.executeMove(edge, "F1,0,0;F4,1,0") as Pearl.State
        assertEquals(0, flipped.lines[0])
        assertEquals(0, flipped.lines[1])

        val solveFlag = Pearl.executeMove(s, "S") as Pearl.State
        assertTrue(solveFlag.usedSolve)
    }

    @Test
    fun `the move grammar is loose like sscanf`() {
        val s = state("y", "5x5de")
        assertNotNull(Pearl.executeMove(s, "F 1, 0, 0;F 4, 1, 0"))
        assertNotNull(Pearl.executeMove(s, "F+1,+0,+0;F+4,+1,+0"))
        assertNotNull(Pearl.executeMove(s, "F01,00,00;F04,01,00"))
        assertNotNull(Pearl.executeMove(s, "F1,0,0;F4,1,0;"))
        assertNotNull(Pearl.executeMove(s, ""))
        // Saturation makes an overflowing number out of range, not a crash.
        assertNull(Pearl.executeMove(s, "F1,99999999999999999999,0;F4,1,0"))
        assertNull(Pearl.executeMove(s, "F99999999999999999999,0,0;F4,1,0"))
    }

    @Test
    fun `bad moves are rejected`() {
        val s = state("y", "5x5de")
        assertNull(Pearl.executeMove(s, "X"))
        assertNull(Pearl.executeMove(s, "F1,0"))
        assertNull(Pearl.executeMove(s, "F1,0,0x;F4,1,0"))
        assertNull(Pearl.executeMove(s, "F1,0,0;;F4,1,0"))
        assertNull(Pearl.executeMove(s, "F16,0,0;F4,1,0"))
        assertNull(Pearl.executeMove(s, "F-1,0,0;F4,1,0"))
        assertNull(Pearl.executeMove(s, "F1,5,0;F4,4,0"))
        assertNull(Pearl.executeMove(s, "F1,0,5;F4,1,5"))
        // H must be the whole remaining text.
        assertNull(Pearl.executeMove(s, "H;F1,0,0;F4,1,0"))
        assertNull(Pearl.executeMove(s, "H;"))
        // A dangling half-edge fails the completion check.
        assertNull(Pearl.executeMove(s, "F1,0,0"))
    }

    @Test
    fun `a line and a mark collide per cell`() {
        val s = state("y", "5x5de")
        // The same cell, the same bit.
        assertNull(Pearl.executeMove(s, "F1,0,0;F4,1,0;M4,1,0"))
        assertNull(Pearl.executeMove(s, "M1,0,0;F1,0,0;F4,1,0"))
        // The cross-cell pair cannot be produced by a valid move either:
        // the per-cell test passes, but the reciprocal line needed by
        // check_completion collides with the mark. This is the observable
        // consequence of the C per-cell test.
        assertNull(Pearl.executeMove(s, "F1,0,0;M4,1,0"))
        assertNull(Pearl.executeMove(s, "M1,0,0;F1,0,0;F4,1,0"))
    }

    @Test
    fun `the hint keeps undecided lines and does not set used solve`() {
        val blank = Midend.fromGameId(Pearl, "8x8:zzl")
        val line = Pearl.executeMove(blank.state, "F1,0,0;F4,1,0") as Pearl.State
        val hinted = Pearl.executeMove(line, "H") as Pearl.State
        assertTrue(line.lines.contentEquals(hinted.lines), "the ambiguous cells stay")
        assertFalse(hinted.usedSolve)
        // Marks never overlap a line after a hint.
        for (i in hinted.lines.indices) {
            assertEquals(0, hinted.marks[i] and hinted.lines[i])
        }
        // A second hint is accepted and changes nothing.
        val again = Pearl.executeMove(hinted, "H") as Pearl.State
        assertTrue(hinted.lines.contentEquals(again.lines))
    }

    @Test
    fun `the hint completes a fresh easy board and raises the event`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'H'.code, 31))
        assertEquals(1, midend.status())
        assertFalse((midend.state as Pearl.State).usedSolve)
        assertTrue(midend.takeCompletedEvent(), "H does not set used_solve")
        assertEquals("H", midend.serialise().value("MOVE"))
    }

    @Test
    fun `a solve does not raise the completion event`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertFalse(midend.takeCompletedEvent())
    }

    // ------------------------------------------------------------------
    // Completion and history.
    // ------------------------------------------------------------------

    @Test
    fun `completed is sticky and undo restores the errors`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        val solved = midend.state
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertEquals(solved, midend.state)

        // A move that leaves a valid path keeps the sticky flag.
        val solvedState = midend.state as Pearl.State
        val edge = firstEdge(solvedState)
        val broken = Pearl.executeMove(
            solvedState,
            "N${edge[0]},${edge[1]},${edge[2]};" +
                "N${edge[3]},${edge[4]},${edge[5]}",
        ) as Pearl.State
        assertEquals(1, Pearl.status(broken), "the sticky status survives")
        assertFalse(Pearl.changedState(midend.ui, solvedState, broken))
    }

    /** The first laid edge as `(dir, x, y, F(dir), x2, y2)`. */
    private fun firstEdge(s: Pearl.State): List<Int> {
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val line = s.lines[y * s.width + x]
                if ((line and 1) != 0) {
                    return listOf(1, x, y, 4, x + 1, y)
                }
                if ((line and 8) != 0) {
                    return listOf(8, x, y, 2, x, y + 1)
                }
            }
        }
        error("the solved board has no edge")
    }

    @Test
    fun `changed state excludes a solve and includes a hint`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        val before = midend.state
        val hinted = Pearl.executeMove(before, "H") as Pearl.State
        assertTrue(Pearl.changedState(midend.ui, before, hinted))
        val solved = Pearl.executeMove(before, Pearl.solve(before)!!) as Pearl.State
        assertTrue(solved.usedSolve)
        assertFalse(Pearl.changedState(midend.ui, before, solved))
        assertFalse(Pearl.changedState(midend.ui, null, hinted))
    }

    @Test
    fun `restart starts from a cleared board`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        assertTrue(midend.processKey(0, 0, 'H'.code, 31) == KeyOutcome.SomeEffect)
        assertEquals(1, midend.status())
        assertTrue(midend.restart())
        assertEquals(0, midend.status())
        assertEquals(3, midend.stateCount)
        val restarted = midend.state as Pearl.State
        assertTrue(restarted.lines.all { it == 0 })
        assertTrue(restarted.errors.all { it == 0 })
        assertTrue(restarted.marks.all { it == 0 })
    }

    // ------------------------------------------------------------------
    // Text, status, keys, and geometry.
    // ------------------------------------------------------------------

    @Test
    fun `the text form has the C grammar`() {
        val blank = state("y", "5x5de")
        val blankText = Pearl.textFormat(blank)
        assertEquals(9, blankText.count { it == '\n' })
        assertTrue(blankText.endsWith("\n"))
        assertEquals("+   +   +   +   +", blankText.split('\n')[0])

        val lined = Pearl.executeMove(blank, "F1,0,0;F4,1,0") as Pearl.State
        assertEquals("+---+   +   +   +", Pearl.textFormat(lined).split('\n')[0])

        val marked = Pearl.executeMove(blank, "M8,0,0;M2,0,1") as Pearl.State
        val markText = Pearl.textFormat(marked)
        assertEquals("x" + " ".repeat(16), markText.split('\n')[1])

        val horizontalMark = Pearl.executeMove(blank, "M1,0,0;M4,1,0") as Pearl.State
        assertEquals("+ x +   +   +   +", Pearl.textFormat(horizontalMark).split('\n')[0])

        // Clue letters: 8x8 Tricky starts with a black and a white clue.
        val clueText = Pearl.textFormat(Midend.newGame(Pearl, "8x8dt", SEED).state)
        assertEquals("+   B   +   +   +   +   W   +", clueText.split('\n')[0])
        assertTrue(clueText.contains('B'))
        assertTrue(clueText.contains('W'))
    }

    @Test
    fun `status and status text are the C values`() {
        val s = state("y", "5x5de")
        assertEquals(0, Pearl.status(s))
        assertEquals("", Pearl.statusText(s, Pearl.newUi(s)))
        assertFalse(Pearl.wantsStatusBar)
    }

    @Test
    fun `request keys is the C null hook`() {
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT),
            Pearl.requestKeys(params("8x8dt")),
        )
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_ONLY),
            Pearl.requestKeys(params("8x8dt"), fullCursor = true),
        )
    }

    @Test
    fun `current key label follows the cursor and the drag`() {
        val s = state("y", "5x5de")
        val ui = Pearl.newUi(s) as Pearl.Ui
        assertEquals("", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.cursorActive = true
        assertEquals("Start", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))
        ui.ndragcoords = 0
        assertEquals("Stop", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("Cancel", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))
        ui.cursorActive = false
        assertEquals("", Pearl.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("", Pearl.currentKeyLabel(ui, s, 'a'.code))
    }

    @Test
    fun `compute size reads the appearance preference`() {
        val p = params("8x8dt")
        val ui = Pearl.newUi(state("y", "5x5de")) as Pearl.Ui
        assertEquals(Size(278, 278), Pearl.computeSize(p, ui, 31))
        assertEquals(Size(422, 422), Pearl.computeSize(p, ui, 48))
        assertEquals(Size(566, 566), Pearl.computeSize(p, ui, 63))
        assertEquals(Size(728, 728), Pearl.computeSize(p, ui, 82))
        assertEquals(Size(890, 890), Pearl.computeSize(p, ui, 100))
        ui.appearance = Pearl.Appearance.LOOPY
        assertEquals(Size(254, 254), Pearl.computeSize(p, ui, 31))
        assertEquals(Size(386, 386), Pearl.computeSize(p, ui, 48))
        assertEquals(Size(518, 518), Pearl.computeSize(p, ui, 63))
        assertEquals(Size(668, 668), Pearl.computeSize(p, ui, 82))
        assertEquals(Size(816, 816), Pearl.computeSize(p, ui, 100))
        // The two-argument form is the traditional appearance.
        assertEquals(Size(278, 278), Pearl.computeSize(p, 31))
        assertEquals(
            Size(278, 278),
            Pearl.computeSize(p, Game.NoUi, 31),
        )
    }

    @Test
    fun `the cursor rectangle uses the appearance border`() {
        val s = state("y", "5x5de")
        val ui = Pearl.newUi(s) as Pearl.Ui
        assertNull(Pearl.cursorLocation(ui, s, 31))
        ui.cursorActive = true
        assertEquals(
            Game.CursorRect(15, 15, 31, 31),
            Pearl.cursorLocation(ui, s, 31),
        )
        ui.curx = 2
        ui.cury = 3
        assertEquals(
            Game.CursorRect(77, 108, 31, 31),
            Pearl.cursorLocation(ui, s, 31),
        )
        ui.appearance = Pearl.Appearance.LOOPY
        assertEquals(
            Game.CursorRect(65, 96, 31, 31),
            Pearl.cursorLocation(ui, s, 31),
        )
    }

    @Test
    fun `the palette is the C palette`() {
        val colours = Pearl.colours()
        assertEquals(30, colours.size)
        val expected = listOf(
            listOf(800, 800, 800),
            listOf(967, 967, 967),
            listOf(633, 633, 633),
            listOf(0, 0, 0),
            listOf(1000, 1000, 1000),
            listOf(1000, 0, 0),
            listOf(400, 400, 400),
            listOf(1000, 1000, 1000),
            listOf(0, 0, 1000),
            listOf(800, 800, 1000),
        )
        for (index in expected.indices) {
            for (channel in 0 until 3) {
                assertEquals(
                    expected[index][channel],
                    (colours[index * 3 + channel] * 1000).roundToInt(),
                    "$index/$channel",
                )
            }
        }
        // A different shell background moves only index 0 and the two
        // highlight colours.
        val themed = Pearl.colours(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertEquals(200, (themed[0] * 1000).roundToInt())
        assertEquals(400, (themed[1] * 1000).roundToInt())
        assertEquals(600, (themed[2] * 1000).roundToInt())
        assertEquals(0, (themed[3 * 3] * 1000).roundToInt())
        assertEquals(1000, (themed[4 * 3] * 1000).roundToInt())
    }

    @Test
    fun `the loopy appearance draws the loopy geometry`() {
        val midend = Midend.fromGameId(Pearl, "5x5:y")
        (midend.ui as Pearl.Ui).appearance = Pearl.Appearance.LOOPY
        val sink = RecordingSink()
        midend.recordNewGameTrace(sink)

        assertTrue(sink.ops.contains("rect:0:0:161:161:0"), "the midend clear: ${sink.ops}")
        assertTrue(sink.ops.contains("update:0:0:161:161"))
        assertFalse(
            sink.ops.any { it.startsWith("rect:2:2:") },
            "no black grid block in LOOPY: ${sink.ops}",
        )
        assertTrue(sink.ops.contains("clip:3:3:31:31"))
        assertTrue(sink.ops.contains("rect:3:3:31:31:0"))
        // The small grid dot and the centre-to-edge lines.
        assertTrue(sink.ops.contains("circle:18:18:3:6:6"))
        assertTrue(sink.ops.contains("line:18:18:33:18:6"))
        assertTrue(sink.ops.contains("line:18:18:18:33:6"))
        assertEquals(1, sink.ops.count { it == "changed_state:false:false" })
    }

    @Test
    fun `the first draw paints every cell and a warm draw paints none`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        val sink = RecordingSink()
        midend.recordNewGameTrace(sink)
        assertEquals(36, sink.ops.count { it.startsWith("clip:") })
        assertEquals(36, sink.ops.count { it == "unclip" })

        sink.ops.clear()
        midend.recordRedraw(sink)
        assertEquals(listOf("start_draw", "end_draw"), sink.ops)
    }

    @Test
    fun `a mark changes the board and an inert click does not`() {
        val midend = Midend.newGame(Pearl, "6x6de", SEED)
        val before = midend.serialise().serialise()
        val movesBefore = midend.serialise().values("MOVE").size
        val textBefore = midend.textFormat()

        val tile = 31
        val border = tile / 2
        val cx = border + tile / 2
        val cy = border + tile / 2
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(cx, cy, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(cx, cy, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals(before, midend.serialise().serialise())
        assertEquals(movesBefore, midend.serialise().values("MOVE").size)
        assertEquals(textBefore, midend.textFormat())

        // A real click just outside the centre radius lays a mark.
        val x = cx
        val y = cy + tile / 4 + 1
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(x, y, Buttons.RIGHT_BUTTON, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(x, y, Buttons.RIGHT_RELEASE, tile),
        )
        assertNotEquals(before, midend.serialise().serialise())
        assertEquals(movesBefore + 1, midend.serialise().values("MOVE").size)
        assertNotEquals(textBefore, midend.textFormat())
    }

    /** Records every draw operation as `name:a:b:...`, in call order. */
    private class RecordingSink : DrawSink {
        val ops = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state:$canUndo:$canRedo"
        }

        override fun startDraw() {
            ops += "start_draw"
        }

        override fun endDraw() {
            ops += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect:$x:$y:$width:$height:$colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line:$x1:$y1:$x2:$y2:$colour"
        }

        override fun thickLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            thickness: Float,
            colour: Int,
        ) {
            ops += "thick_line"
        }

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle:$cx:$cy:$radius:$fillColour:$outlineColour"
        }

        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "thick_circle"
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly"
        }

        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "thick_poly"
        }

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {
            ops += "text:$text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip:$x:$y:$width:$height"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update:$x:$y:$width:$height"
        }

        override fun status(text: String) {
            ops += "status:$text"
        }

        override fun inertiaFollow(isSolved: Boolean) {}

        override fun blitterNew(width: Int, height: Int): Int = 1

        override fun blitterFree(id: Int) {}

        override fun blitterSave(id: Int, x: Int, y: Int) {}

        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
