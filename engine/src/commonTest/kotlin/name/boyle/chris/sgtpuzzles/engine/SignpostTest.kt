package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: signpost.
 *
 * Kotlin-only pins for the parameter quirks, the description decoder,
 * the text-format definition, the move grammar, the cursor labels, the
 * two-state solver and its two error strings, `changed_state`, the
 * palette arithmetic, and the first versus warm redraw. The frozen
 * traces and the oracle comparisons live in the parity module.
 */
class SignpostTest {

    private companion object {
        const val TILE = 48
        const val SEED = "12345"
        const val DESC = "1dcegcefebeghcbh16a"
        const val SOLVE = "S1d9c11e10g13c4e14f7e3b5e2g8h15c6b12h16a"

        const val GOLDEN_4X4 = "4x4:16ag1ffcddachhfcaga"
        const val GOLDEN_4X4C = "4x4:1dcegcefebeghcbh16a"
        const val GOLDEN_5X5 = "5x5:eeffecgdfhceheaa21cach1cc7a25ah"
        const val GOLDEN_5X5C = "5x5:1cdcgfdeageeefeg19c8cgaaachc25a"
        const val GOLDEN_6X6C = "6x6:1ccecceddce6f5gccgdf15gba8ffdhaac30h28fgbccaa36a"
        const val GOLDEN_7X7C =
            "7x7:1eeeegefdadfe40ggcadb25d9eheee45c12bhaebhg42g10hfb37caeca38a44b18bbbgh49a"
    }

    private fun params(text: String): Signpost.Params =
        Signpost.decodeParams(text) as Signpost.Params

    private fun state(text: String = "4x4c", desc: String = DESC): Signpost.State =
        Signpost.newState(params(text), desc) as Signpost.State

    private fun descOf(text: String, seed: String): String =
        Signpost.newDesc(params(text), RandomState.fromSeed(seed)).text

    private fun idOf(text: String, seed: String): String =
        Midend.newGame(Signpost, text, seed).id()

    private fun ui(): Signpost.Ui = Signpost.Ui()

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `defaults and presets match the C tables`() {
        assertEquals(Signpost.Params(4, 4, true), Signpost.defaultParams())
        val presets = Signpost.presets()
        assertEquals(6, presets.size)
        assertEquals(
            listOf("4x4c", "4x4", "5x5c", "5x5", "6x6c", "7x7c"),
            presets.map { it.params },
        )
        assertEquals(
            listOf("4x4", "4x4, free ends", "5x5", "5x5, free ends", "6x6", "7x7"),
            presets.map { it.title },
        )
        assertEquals(48, Signpost.preferredTileSize)
        assertFalse(Signpost.wantsStatusBar)
        assertTrue(Signpost.canSolve)
        assertTrue(Signpost.canFormatAsText)
        assertEquals("Signpost", Signpost.name)
        assertEquals("signpost", Signpost.backendName)
    }

    @Test
    fun `decode follows atoi and the digit-only skip`() {
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4"))
        assertEquals(Signpost.Params(4, 4, true), Signpost.decodeParams("4c"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4x4junk"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("+4x4"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams(" 4x4"))
        assertEquals(Signpost.Params(4, -4, false), Signpost.decodeParams("4x-4"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("04x04"))
        assertEquals(Signpost.Params(4, 4, true), Signpost.decodeParams("4x4c5"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4x4x4"))
        assertEquals(Signpost.Params(4, 4, false), Signpost.decodeParams("4X4"))
        assertEquals(Signpost.Params(0, 0, false), Signpost.decodeParams(""))
        assertEquals(Signpost.Params(0, 4, false), Signpost.decodeParams("0x4"))
        assertEquals(Signpost.Params(4, 0, false), Signpost.decodeParams("4x"))
        assertEquals(Signpost.Params(7, 7, true), Signpost.decodeParams("7x7cc"))
        // C `atoi` overflow is undefined; the port saturates.
        assertEquals(
            Signpost.Params(Int.MAX_VALUE, 7, false),
            Signpost.decodeParams("99999999999x7"),
        )
    }

    @Test
    fun `encode drops the corner flag in the short form`() {
        val p = Signpost.Params(5, 5, true)
        assertEquals("5x5", Signpost.encodeParams(p, false))
        assertEquals("5x5c", Signpost.encodeParams(p, true))
        assertEquals("4x4c", Signpost.encodeParams(Signpost.defaultParams(), true))
        assertEquals("4x4", Signpost.encodeParams(Signpost.defaultParams(), false))
        assertEquals("5x5", Signpost.encodeParams(Signpost.Params(5, 5, false), true))
    }

    @Test
    fun `validation follows the C order and messages`() {
        assertEquals(
            "Width must be at least one",
            Signpost.validateParams(Signpost.Params(0, 0, true), true),
        )
        assertEquals(
            "Height must be at least one",
            Signpost.validateParams(Signpost.Params(4, 0, true), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Signpost.validateParams(Signpost.Params(46341, 46341, true), true),
        )
        assertEquals(
            "Width and height cannot both be one",
            Signpost.validateParams(Signpost.Params(1, 1, true), true),
        )
        assertNull(Signpost.validateParams(Signpost.Params(1, 1, true), false))
        assertNull(Signpost.validateParams(Signpost.Params(46340, 46340, true), true))
        assertEquals(
            "Width times height must not be unreasonably large",
            Signpost.validateParams(Signpost.Params(65536, 65536, true), true),
        )
        // Rule 3 runs after rules 1 and 2, so a zero height never
        // divides by zero.
        assertEquals(
            "Height must be at least one",
            Signpost.validateParams(Signpost.Params(Int.MAX_VALUE, 0, true), true),
        )
    }

    // ------------------------------------------------------------------
    // Descriptions.
    // ------------------------------------------------------------------

    @Test
    fun `the golden descriptions are generated exactly`() {
        assertEquals(GOLDEN_4X4, idOf("4x4", SEED))
        assertEquals(GOLDEN_4X4C, idOf("4x4c", SEED))
        assertEquals(GOLDEN_5X5, idOf("5x5", SEED))
        assertEquals(GOLDEN_5X5C, idOf("5x5c", SEED))
        assertEquals(GOLDEN_6X6C, idOf("6x6c", SEED))
        assertEquals(GOLDEN_7X7C, idOf("7x7c", SEED))
        assertEquals("4x4:ec16afcad1faaegbhca", idOf("4x4", "3"))
    }

    @Test
    fun `generation retries follow the fill and strip streams`() {
        val free = Signpost.newDescWithAttempts(
            params("4x4"),
            RandomState.fromSeed("3"),
        )
        assertEquals(2, free.fillRetries)
        assertEquals(0, free.stripRetries)
        assertEquals("ec16afcad1faaegbhca", free.desc)

        // The strip retry is dead for every preset: the add loop always
        // reaches a fully numbered board, which the solver finishes.
        for (text in listOf("4x4", "4x4c", "5x5", "5x5c", "6x6c", "7x7c")) {
            for (seed in 0..20) {
                val g = Signpost.newDescWithAttempts(
                    params(text),
                    RandomState.fromSeed(seed.toString()),
                )
                assertEquals(0, g.stripRetries, "'$text' seed=$seed")
                assertEquals(
                    Signpost.encodeParams(params(text), false) + ":" + g.desc,
                    idOf(text, seed.toString()),
                )
            }
        }
    }

    @Test
    fun `a generated description has no aux text`() {
        for (text in listOf("4x4", "4x4c", "5x5c", "6x6c")) {
            val d = Signpost.newDesc(params(text), RandomState.fromSeed(SEED))
            assertNull(d.auxInfo)
            assertNull(Signpost.validateDesc(params(text), d.text))
        }
    }

    @Test
    fun `the description grammar reports the C messages`() {
        val p = params("4x4")
        val blank = "a".repeat(16)
        assertNull(Signpost.validateDesc(p, blank))
        assertNull(Signpost.validateDesc(p, "0a".repeat(16)))
        assertNull(Signpost.validateDesc(p, "16a" + "a".repeat(15)))
        assertNull(Signpost.validateDesc(p, "1a".repeat(16)))
        assertEquals(
            "Game description shorter than expected",
            Signpost.validateDesc(p, "a".repeat(15)),
        )
        assertEquals(
            "Game description longer than expected",
            Signpost.validateDesc(p, blank + "a"),
        )
        assertEquals(
            "Number out of range",
            Signpost.validateDesc(p, "17a" + "a".repeat(15)),
        )
        assertEquals(
            "Invalid character in game description",
            Signpost.validateDesc(p, "z" + "a".repeat(15)),
        )
        assertEquals(
            "Invalid character in game description",
            Signpost.validateDesc(p, "1a" + "A" + "a".repeat(14)),
        )

        // A 1x1 description is playable even though the full validation
        // refuses the parameters.
        assertEquals(
            "Width and height cannot both be one",
            Signpost.validateParams(Signpost.Params(1, 1, false), true),
        )
        val one = Signpost.newState(Signpost.Params(1, 1, false), "1a") as Signpost.State
        assertEquals(1, one.nums[0])
        assertEquals(0, Signpost.status(one))

        // 0a is an empty cell, 16a is valid on 4x4, and a repeated
        // number is accepted by validation and marked by completion.
        val zero = state("4x4", "0a" + "a".repeat(15))
        assertEquals(0, zero.nums[0])
        assertEquals(0, zero.flags[0])
        val sixteen = state("4x4", "16a" + "a".repeat(15))
        assertEquals(16, sixteen.nums[0])
        assertEquals(Signpost.FLAG_IMMUTABLE, sixteen.flags[0])
        val repeated = state("4x4", "1a".repeat(16))
        assertEquals(Signpost.FLAG_ERROR, repeated.flags[0] and Signpost.FLAG_ERROR)
        assertEquals(Signpost.FLAG_ERROR, repeated.flags[1] and Signpost.FLAG_ERROR)
        assertFalse(repeated.completed)
        assertEquals(0, Signpost.status(repeated))
    }

    @Test
    fun `a wrong consecutive pair marks both cells in error`() {
        val wrong = state("2x2", "1a2aaa")
        assertEquals(Signpost.FLAG_IMMUTABLE or Signpost.FLAG_ERROR, wrong.flags[0])
        assertEquals(Signpost.FLAG_IMMUTABLE or Signpost.FLAG_ERROR, wrong.flags[1])
        assertFalse(wrong.completed)
        assertEquals(0, Signpost.status(wrong))
        assertEquals(
            "2x2:1a2aaa",
            Midend.fromGameId(Signpost, "2x2:1a2aaa").id(),
        )
    }

    @Test
    fun `the 1x1 generator guard draws nothing`() {
        val random = RandomState.fromSeed("12345")
        val before = random.encodeState()
        val d = Signpost.newDesc(Signpost.Params(1, 1, true), random)
        assertEquals("1a", d.text)
        assertEquals(before, random.encodeState())
    }

    @Test
    fun `a full description loads at status 0`() {
        // The `S` payload without its prefix is a complete board. C's
        // new_game does not set `completed`, so the status stays open.
        val full = state("4x4c", SOLVE.substring(1))
        assertEquals(0, Signpost.status(full))
        assertFalse(full.completed)
        // check_completion made the links; the final number has no
        // outgoing link by definition, and the first has no incoming one.
        assertEquals(15, full.next.count { it != -1 })
        assertEquals(15, full.prev.count { it != -1 })
        assertEquals(1, full.nums[0])
        assertEquals(16, full.nums[15])
    }

    @Test
    fun `update_numbers keeps a stale numsi n for the completion check`() {
        // A 2x2 board whose only immutable number is 3. The first link
        // numbers the second cell 4 through the chain walk, so numsi[4]
        // points at it. The cut removes the cell, but the clear loop stops
        // at n-1, so numsi[4] stays stale. check_completion reads it and
        // links 3 to the stale cell again.
        val loaded = state("2x2", "3ceaa")
        val linked = Signpost.executeMove(loaded, "L0,0-1,0") as Signpost.State
        assertEquals(1, linked.numsi[4])
        assertEquals(4, linked.nums[1])

        val cut = Signpost.executeMove(linked, "C1,0") as Signpost.State
        assertEquals(1, cut.numsi[4])
        assertEquals(1, cut.next[0])
        assertEquals(0, cut.prev[1])
        assertEquals(0, cut.nums[1])
        assertEquals(Signpost.FLAG_ERROR, cut.flags[1] and Signpost.FLAG_ERROR)

        // A later move renumbers the stale cell. If the clear covered n,
        // check_completion would see numsi[4] as missing and the cell
        // would stay isolated with no number.
        val moved = Signpost.executeMove(cut, "L0,1-0,0") as Signpost.State
        assertEquals(4, moved.nums[1])
        assertEquals(1, moved.numsi[4])
    }

    @Test
    fun `the state fields match the decoded description`() {
        val s = state()
        assertEquals(4, s.w)
        assertEquals(4, s.h)
        assertEquals(16, s.n)
        assertEquals(16, s.dirs.size)
        assertEquals(dirsFromDesc(), s.dirs.toList())
        assertEquals(1, s.nums[0])
        assertEquals(16, s.nums[15])
        assertEquals(0, s.nums[1])
        assertEquals(Signpost.FLAG_IMMUTABLE, s.flags[0])
        assertEquals(Signpost.FLAG_IMMUTABLE, s.flags[15])
        assertEquals(-1, s.next[0])
        assertEquals(-1, s.prev[0])
        assertFalse(s.completed)
        assertFalse(s.usedSolve)
        assertFalse(s.impossible)
        // numsi indexes the immutable numbers, not the empty cells.
        assertEquals(0, s.numsi[1])
        assertEquals(15, s.numsi[16])
        assertEquals(-1, s.numsi[2])
        assertEquals(-1, s.numsi[0])
    }

    private fun dirsFromDesc(): List<Int> {
        val out = ArrayList<Int>()
        var number = 0
        for (c in "1dcegcefebeghcbh16a") {
            if (c in '0'..'9') {
                number = number * 10 + (c - '0')
            } else {
                out += c - 'a'
                number = 0
            }
        }
        assertEquals(16, out.size)
        return out
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar follows sscanf`() {
        val s = state()
        val link = Signpost.executeMove(s, "L0,0-1,1") as Signpost.State
        assertEquals(1, link.nums[0])
        assertEquals(2, link.nums[1 * 4 + 1])
        assertEquals(5, link.numsi[2])
        assertEquals(0, link.nums[5] / (link.n + 1))

        // Leading white space, signs, leading zeros, and trailing junk.
        assertNotNull(Signpost.executeMove(s, "L+0,+0-01,01"))
        assertNotNull(Signpost.executeMove(s, "L0,0-1,1junk"))
        assertNotNull(Signpost.executeMove(s, "L 0,0-1,1"))
        assertNotNull(Signpost.executeMove(s, "L0, 0-1,1"))
        assertNotNull(Signpost.executeMove(s, "L0,0-1, 1"))
        // A literal must match at once, and `%c` does not skip space.
        assertNull(Signpost.executeMove(s, " L0,0-1,1"))
        assertNull(Signpost.executeMove(s, " 0,0"))
        // Not pointing, the same cell, out of the grid, wrong distance.
        assertNull(Signpost.executeMove(s, "L0,0-0,1"))
        assertNull(Signpost.executeMove(s, "L0,0-0,0"))
        assertNull(Signpost.executeMove(s, "L0,0-9,9"))
        assertNull(Signpost.executeMove(s, "L0,0-3,3"))
        // Junk and the wrong case.
        assertNull(Signpost.executeMove(s, "Z1,2"))
        assertNull(Signpost.executeMove(s, ""))
        assertNull(Signpost.executeMove(s, "h"))
        assertNotNull(Signpost.executeMove(s, "H"))

        // A cut needs a linked cell and a `C` or `X`.
        assertNull(Signpost.executeMove(s, "C0,0"))
        assertNull(Signpost.executeMove(s, "Q0,0"))
        val cut = Signpost.executeMove(link, "C0,0")
        assertNotNull(cut)
        assertEquals(-1, (cut as Signpost.State).next[0])

        // The `S` payload is validated against a fresh parameter struct.
        assertNull(Signpost.executeMove(s, "S1a"))
        val solved = Signpost.executeMove(s, SOLVE) as Signpost.State
        assertTrue(solved.completed)
        assertTrue(solved.usedSolve)
        assertEquals(1, Signpost.status(solved))
    }

    @Test
    fun `arrow chaining numbers the chain and a loop is refused`() {
        val s = state()
        val first = Signpost.executeMove(s, "L1,0-2,0") as Signpost.State
        assertEquals(1, first.nums[1] / (first.n + 1))
        assertEquals(0, first.nums[1] % (first.n + 1))
        assertEquals(1, first.nums[2] % (first.n + 1))
        assertTrue(Signpost.textFormat(first).contains("a 0"))

        // A closed chain: the last arrow would merge two cells of one
        // DSF class.
        val loop = Signpost.newState(Signpost.Params(2, 2, false), "ceag") as Signpost.State
        val a = Signpost.executeMove(loop, "L0,0-1,0")!!
        val b = Signpost.executeMove(a, "L1,0-1,1")!!
        val c = Signpost.executeMove(b, "L0,1-0,0")!!
        assertNull(Signpost.executeMove(c, "L0,1-0,0"))
    }

    @Test
    fun `completion is sticky and a cut keeps it`() {
        val solved = Signpost.executeMove(state(), SOLVE) as Signpost.State
        assertTrue(solved.completed)
        assertEquals(1, Signpost.status(solved))
        val cut = Signpost.executeMove(solved, "C0,0") as Signpost.State
        assertTrue(cut.completed)
        assertEquals(1, Signpost.status(cut))
        // A cut in the solved board is re-made by check_completion,
        // because every consecutive number still points at the next.
        assertNotEquals(-1, cut.next[0])
        // Undo restores an older state, so the history, not the game,
        // clears the visible status.
        val midend = Midend.newGame(Signpost, "4x4c", SEED)
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertTrue(midend.undo())
        assertEquals(0, midend.status())
    }

    @Test
    fun `interpretMove classifies clicks and keys`() {
        val s = state()
        val u = ui()
        // A hidden cursor key shows the cursor.
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, u, 0, 0, Buttons.CURSOR_RIGHT, TILE),
        )
        assertTrue(u.cshow)
        assertEquals(1, u.cx)
        assertEquals(0, u.cy)
        // A visible cursor key with no movement is a no-effect key.
        assertEquals(
            MoveResult.NoEffect,
            Signpost.interpretMove(s, u, 0, 0, Buttons.CURSOR_UP, TILE),
        )
        // A cursor select starts a drag, a second one on the same cell
        // ends it without a move.
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, u, 0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertTrue(u.dragging)
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, u, 0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertFalse(u.dragging)

        // A press in the margin returns unused but clears the cursor;
        // the stale ring stays on screen until the next redraw.
        u.cshow = true
        u.cx = 1
        u.cy = 1
        assertEquals(
            MoveResult.Unused,
            Signpost.interpretMove(s, u, 0, 0, Buttons.LEFT_BUTTON, TILE),
        )
        assertFalse(u.cshow)
        assertFalse(u.dragging)

        // A left press on the final immutable number is unused.
        assertEquals(
            MoveResult.Unused,
            Signpost.interpretMove(s, u, 3 * TILE + 24, 3 * TILE + 24, Buttons.LEFT_BUTTON, TILE),
        )
        // A right press on the first immutable number is unused.
        assertEquals(
            MoveResult.Unused,
            Signpost.interpretMove(s, u, 24, 24, Buttons.RIGHT_BUTTON, TILE),
        )

        // A real left drag links.
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, u, 24, 24, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, u, 72, 72, Buttons.LEFT_DRAG, TILE),
        )
        val move = Signpost.interpretMove(s, u, 72, 72, Buttons.LEFT_RELEASE, TILE)
        assertEquals(MoveResult.Move("L0,0-1,1"), move)

        // The other direction starts at the target and reverses.
        val r = ui()
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, r, 72, 72, Buttons.RIGHT_BUTTON, TILE),
        )
        assertEquals(
            MoveResult.Move("L0,0-1,1"),
            Signpost.interpretMove(s, r, 24, 24, Buttons.RIGHT_RELEASE, TILE),
        )

        // A release outside the board cuts a linked cell.
        val linked = Signpost.executeMove(s, "L0,0-1,1")!!
        val cutUi = ui()
        Signpost.interpretMove(linked, cutUi, 24, 24, Buttons.LEFT_BUTTON, TILE)
        assertEquals(
            MoveResult.Move("C0,0"),
            Signpost.interpretMove(linked, cutUi, -10, -10, Buttons.LEFT_RELEASE, TILE),
        )
        // An isolated cell is a UI update, not a rejected move.
        val isolated = ui()
        Signpost.interpretMove(s, isolated, 72, 24, Buttons.LEFT_BUTTON, TILE)
        assertEquals(
            MoveResult.UiUpdate,
            Signpost.interpretMove(s, isolated, -10, -10, Buttons.LEFT_RELEASE, TILE),
        )

        // x, X, and backspace cut only a linked cell with a visible
        // cursor.
        val keyUi = ui()
        keyUi.cshow = true
        keyUi.cx = 0
        keyUi.cy = 0
        assertEquals(MoveResult.UiUpdate, Signpost.interpretMove(s, keyUi, 0, 0, 'x'.code, TILE))
        assertEquals(
            MoveResult.Move("C0,0"),
            Signpost.interpretMove(linked, keyUi, 0, 0, '\b'.code, TILE),
        )
        assertEquals(
            MoveResult.Move("X0,0"),
            Signpost.interpretMove(linked, keyUi, 0, 0, 'X'.code, TILE),
        )
        keyUi.cshow = false
        assertEquals(MoveResult.Unused, Signpost.interpretMove(linked, keyUi, 0, 0, 'x'.code, TILE))
    }

    // ------------------------------------------------------------------
    // Solver and text.
    // ------------------------------------------------------------------

    @Test
    fun `the solver returns the C payload and the fallback`() {
        val s = state()
        val attempt = Signpost.solveAttempt(s, null)
        assertEquals(SOLVE, attempt.move)
        assertNull(attempt.error)
        // The aux text is not read.
        assertEquals(SOLVE, Signpost.solveAttempt(s, "ignored").move)
        assertEquals(SOLVE, Signpost.solve(s, "ignored"))

        // The current state is tried first: a solved board returns its
        // own payload.
        val solved = Signpost.executeMove(s, SOLVE)!!
        assertEquals(SOLVE, Signpost.solveAttempt(solved, null).move)

        // A broken current state falls back to the initial state.
        val broken = Signpost.executeMove(s, "L0,0-1,1") as Signpost.State
        assertEquals(-1, Signpost.solveResult(broken))
        val fallback = Signpost.solveAttempt(broken, null)
        assertEquals(SOLVE, fallback.move)
        assertNull(fallback.error)
        assertEquals(1, Signpost.solveResult(s))

        // The two C error strings.
        assertEquals(
            SolveAttemptString("Puzzle is impossible."),
            SolveAttemptString(Signpost.solveAttempt(state("2x2", "aaaa"), null).error),
        )
        assertEquals(
            SolveAttemptString("Unable to solve puzzle."),
            SolveAttemptString(Signpost.solveAttempt(state("4x1", "ccgg"), null).error),
        )
        assertNull(Signpost.solveAttempt(state("2x2", "aaaa"), null).move)
    }

    @Test
    fun `the solve tries the current state before the fallback`() {
        // A real game cannot have two successful but different payloads.
        // A board that the solver completes has one forced solution, so a
        // later state either shares that solution or fails. The order is
        // therefore pinned with a different solved board attached as the
        // fallback: it must never be used while the current state solves.
        val currentBoard = state("3x1", "1c2c3g")
        val otherBoard = state("3x1", "1c2c3c")
        val currentPayload = Signpost.solveAttempt(currentBoard, null).move
        val otherPayload = Signpost.solveAttempt(otherBoard, null).move
        assertEquals("S1c2c3g", currentPayload)
        assertEquals("S1c2c3c", otherPayload)
        assertNotEquals(currentPayload, otherPayload)

        val withFallback = Signpost.State(
            currentBoard.w,
            currentBoard.h,
            currentBoard.dirs,
            currentBoard.nums,
            currentBoard.flags,
            currentBoard.next,
            currentBoard.prev,
            currentBoard.dsf,
            currentBoard.numsi,
            currentBoard.completed,
            currentBoard.usedSolve,
            currentBoard.impossible,
            otherBoard,
        )
        assertEquals(currentPayload, Signpost.solveAttempt(withFallback, null).move)
    }

    /** A tiny wrapper so the two error strings are compared verbatim. */
    private data class SolveAttemptString(val value: String?)

    @Test
    fun `the text form pins the C column bound`() {
        assertTrue(
            Signpost.textFormat(state()).startsWith("SEI E   S   W   \n 1              \n\n"),
        )
        // `w == h`: the four rows of the 4x4 game.
        assertEquals(4, Signpost.textFormat(state()).split("\n\n").size - 1)

        // `w > h`: C uses `h` as the column bound, so the fifth column of
        // a 5x4 board is dropped. Every row is four groups of four.
        val wide = state("5x4", descOf("5x4", SEED))
        val wideText = Signpost.textFormat(wide)
        assertEquals(4, wideText.split("\n\n").size - 1)
        for (line in wideText.lines().filter { it.isNotEmpty() }) {
            assertEquals(16, line.length)
        }

        // `w < h`: C reads past the arrays. The port defines the cells
        // past the end as direction N, number 0, and no flags; the column
        // bound is still `h`, so the wrapped reads inside the array stay.
        val tall = state("4x5", descOf("4x5", SEED))
        val tallText = Signpost.textFormat(tall)
        assertEquals(5, tallText.split("\n\n").size - 1)
        val lines = tallText.lines()
        assertEquals(20, lines[0].length)
        // The last row's fifth group is the out-of-range cell.
        assertEquals("N   ", lines[12].substring(16, 20))
        assertEquals("    ", lines[13].substring(16, 20))
        // An in-array wrap keeps the C read: row y, column 4 is the first
        // cell of row y+1.
        assertEquals(lines[3].substring(0, 2), lines[0].substring(16, 18))

        // 99 cells is the last size that formats. `9x11` is taller than
        // wide, so it is the Kotlin-only defined out-of-range read;
        // `11x9` is deterministic in C and is compared in
        // `SignpostGameTest`.
        val ninetyNine = state("9x11", "a".repeat(99))
        assertEquals(99, ninetyNine.n)
        assertNotEquals("", Signpost.textFormat(ninetyNine))
        assertEquals(11, Signpost.textFormat(ninetyNine).split("\n\n").size - 1)
        assertEquals(44, Signpost.textFormat(ninetyNine).lines().first().length)

        // 100 cells: `can_format_as_text_now` is false; the seam returns
        // the empty string, as the oracle prints NULL.
        val big = state("10x10", "a".repeat(100))
        assertEquals("", Signpost.textFormat(big))
        assertEquals(100, big.n)

        // Empty and full board shapes.
        val empty = state("4x4", "a".repeat(16))
        assertEquals(
            4,
            Signpost.textFormat(empty).split("\n\n").size - 1,
        )
        assertFalse(Signpost.textFormat(empty).lines().any { it.contains(Regex("[0-9]")) })
        val full = state("4x4", SOLVE.substring(1))
        assertTrue(Signpost.textFormat(full).contains("16"))
    }

    // ------------------------------------------------------------------
    // Cursor, keys, changed state.
    // ------------------------------------------------------------------

    @Test
    fun `currentKeyLabel describes the cursor and the drag`() {
        val s = state()
        val u = ui()
        assertEquals("", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        u.cshow = true
        assertEquals("From here", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("To here", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        assertEquals("", Signpost.currentKeyLabel(u, s, 'x'.code))

        // A keyboard drag from the cursor at (0,0) to (1,1).
        u.dragging = true
        u.dragIsFrom = true
        u.sx = 0
        u.sy = 0
        u.cx = 1
        u.cy = 1
        assertEquals("To here", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        // The reverse drag is valid from (0,0) into the origin (1,1).
        u.dragIsFrom = false
        u.sx = 1
        u.sy = 1
        u.cx = 0
        u.cy = 0
        assertEquals("From here", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        u.cx = 2
        u.cy = 1
        assertEquals("Cancel", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("Cancel", Signpost.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
    }

    @Test
    fun `changedState follows the C order and the null guard`() {
        val fresh = state()
        val u = ui()
        assertFalse(Signpost.changedState(u, null, fresh))

        // A solve win does not raise the event.
        val solvedByS = Signpost.executeMove(fresh, SOLVE) as Signpost.State
        assertFalse(Signpost.changedState(u, fresh, solvedByS))

        // A normal win raises it and clears the cursor and the drag.
        val moves = solutionLinks(fresh)
        var current: Signpost.State = fresh
        for (move in moves.dropLast(2)) {
            current = Signpost.executeMove(current, move) as Signpost.State
        }
        u.cshow = true
        u.dragging = true
        // The link from 14 to 15 also lets check_completion make the final
        // 15-to-16 link, so this is the completing move.
        val won = Signpost.executeMove(current, moves[moves.size - 2]) as Signpost.State
        assertTrue(won.completed)
        assertFalse(won.usedSolve)
        assertTrue(Signpost.changedState(u, current, won))
        assertFalse(u.cshow)
        assertFalse(u.dragging)

        // A redo into a completed state raises it again.
        assertTrue(Signpost.changedState(u, current, won))
        // A restart out of the win does not.
        val restarted = Signpost.newState(params("4x4c"), DESC) as Signpost.State
        assertFalse(Signpost.changedState(u, won, restarted))
    }

    /** The `L` moves of the stored solution, in order. */
    private fun solutionLinks(s: Signpost.State): List<String> {
        val solution = Signpost.solveAttempt(s, null).move!!
        val solved = Signpost.executeMove(s, solution) as Signpost.State
        val stateByNumber = IntArray(s.n + 1)
        for (i in 0 until s.n) stateByNumber[solved.nums[i]] = i
        return (1 until s.n).map { number ->
            val from = stateByNumber[number]
            val to = stateByNumber[number + 1]
            "L${from % s.w},${from / s.w}-${to % s.w},${to / s.w}"
        }
    }

    @Test
    fun `the flash preference is UI state only`() {
        val u = ui()
        assertFalse(u.gearMode)
        u.gearMode = true
        assertNull(Signpost.encodeUi(u))
        val midend = Midend.newGame(Signpost, "4x4c", SEED)
        val text = midend.serialise().serialise()
        assertFalse(text.contains("UI"))
        assertFalse(text.contains("gear"))
    }

    @Test
    fun `requestKeys and cursorLocation match the C shape`() {
        val request = Signpost.requestKeys(Signpost.defaultParams())
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, request.arrowMode)
        assertEquals(1, request.labels.size)
        assertEquals("Clear", request.labels[0].name)
        assertEquals('\b'.code, request.labels[0].button)
        assertFalse(request.labels[0].needsArrows)

        val s = state()
        val u = ui()
        assertNull(Signpost.cursorLocation(u, s, TILE))
        u.cshow = true
        u.cx = 2
        u.cy = 3
        assertEquals(
            Game.CursorRect(2 * TILE + 24, 3 * TILE + 24, TILE, TILE),
            Signpost.cursorLocation(u, s, TILE),
        )
    }

    // ------------------------------------------------------------------
    // Geometry, palette, drawing.
    // ------------------------------------------------------------------

    @Test
    fun `computeSize and the palette match the C arithmetic`() {
        assertEquals(Size(240, 240), Signpost.computeSize(Signpost.defaultParams(), 48))
        assertEquals(Size(288, 288), Signpost.computeSize(params("5x5"), 48))
        assertEquals(Size(336, 336), Signpost.computeSize(params("6x6"), 48))
        assertEquals(Size(384, 384), Signpost.computeSize(params("7x7"), 48))
        // An odd tile loses one pixel of border per total, as C does.
        assertEquals(Size(314, 314), Signpost.computeSize(Signpost.defaultParams(), 63))
        assertEquals(Size(188, 188), Signpost.computeSize(params("2x2"), 63))

        val colours = Signpost.colours()
        assertEquals(78, Signpost.NCOLOURS)
        assertEquals(78 * 3, colours.size)
        assertEquals(800, (colours[0] * 1000).roundToInt())
        assertEquals(967, (colours[3] * 1000).roundToInt())
        assertEquals(633, (colours[6] * 1000).roundToInt())
        assertEquals(615, (colours[9] * 1000).roundToInt())
        assertEquals(400, (colours[12] * 1000).roundToInt())
        assertEquals(1000, (colours[15] * 1000).roundToInt())
        assertEquals(0, (colours[16] * 1000).roundToInt())
        assertEquals(200, (colours[18] * 1000).roundToInt())
        assertEquals(1000, (colours[19] * 1000).roundToInt())
        assertEquals(200, (colours[20] * 1000).roundToInt())
        // The last background is the B7/B8 midpoint.
        assertEquals(996, (colours[29 * 3] * 1000).roundToInt())
        assertEquals(903, (colours[29 * 3 + 1] * 1000).roundToInt())
        assertEquals(368, (colours[29 * 3 + 2] * 1000).roundToInt())

        // A shell background moves the derived entries but not the fixed
        // numbered-cell backgrounds.
        val dark = Signpost.colours(floatArrayOf(0.2f, 0.2f, 0.2f))
        assertEquals(200, (dark[0] * 1000).roundToInt())
        assertEquals(996, (dark[14 * 3] * 1000).roundToInt())
        assertEquals(0, (dark[11 * 3] * 1000).roundToInt())
        assertNotEquals(colours[9], dark[9])
    }

    @Test
    fun `the drawstate allocates one blitter and the first draw is full`() {
        val midend = Midend.newGame(Signpost, "4x4c", SEED)
        val sink = OpSink()
        midend.recordNewGameTrace(sink)
        assertEquals("changed_state", sink.ops.first())
        assertEquals("blitter_new:48:48", sink.ops[1])
        assertEquals("start_draw", sink.ops[2])
        assertEquals("rect:0:0:240:240:0", sink.ops[3])
        assertEquals("update:0:0:240:240", sink.ops[sink.ops.size - 2])
        assertEquals("end_draw", sink.ops.last())
        assertEquals(17, sink.ops.count { it == "poly:4" })
        assertEquals(15, sink.ops.count { it == "circle" })
        assertEquals(2, sink.ops.count { it == "text" })

        // A warm frame after a move draws only the changed tiles.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 24, Buttons.LEFT_BUTTON, TILE))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 72, Buttons.LEFT_RELEASE, TILE))
        sink.ops.clear()
        midend.recordRedraw(sink)
        assertEquals("start_draw", sink.ops.first())
        assertEquals("end_draw", sink.ops.last())
        assertFalse(sink.ops.contains("rect:0:0:240:240:0"))
        assertFalse(sink.ops.contains("update:0:0:240:240"))
        assertTrue(sink.ops.contains("update:24:24:48:48"))
        assertTrue(sink.ops.size < 60)
    }

    /**
     * The release frame itself is not in any compared `traceWarm`: the
     * oracle helper records only the final redraw, and the release frame
     * is one of the frames it draws while recording is off. This sink
     * records that frame, so the sprite erase cannot be dropped.
     */
    @Test
    fun `the release frame erases the drag sprite`() {
        val midend = Midend.newGame(Signpost, "4x4c", SEED)
        val sink = OpSink()
        midend.recordNewGameTrace(sink)

        // A press on cell (0,0) and a drag to (1,1) leave a sprite at the
        // press point, which is the top-left corner for tile 48.
        sink.ops.clear()
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 24, Buttons.LEFT_BUTTON, TILE))
        midend.recordRedraw(sink)
        assertTrue(sink.ops.contains("blitter_save:1:0:0"))

        // The release makes the move and clears `ui->dragging`, so the
        // next frame erases the old sprite and saves no new one.
        sink.ops.clear()
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 72, Buttons.LEFT_RELEASE, TILE))
        midend.recordRedraw(sink)
        assertEquals("start_draw", sink.ops[0])
        assertEquals("blitter_load:1:0:0", sink.ops[1])
        assertEquals("update:0:0:48:48", sink.ops[2])
        assertFalse(sink.ops.any { it.startsWith("blitter_save") })
        assertFalse(sink.ops.contains("rect:0:0:240:240:0"))
        assertFalse(sink.ops.contains("update:0:0:240:240"))
        assertEquals("end_draw", sink.ops.last())
        assertEquals(listOf("L0,0-1,1"), midend.serialise().values("MOVE"))
    }

    private class OpSink : DrawSink {
        val ops = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state"
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
            ops += "line"
        }

        override fun thickLine(x1: Int, y1: Int, x2: Int, y2: Int, thickness: Float, colour: Int) {}

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle"
        }
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly:${points.size / 2}"
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
            ops += "text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update:$x:$y:$width:$height"
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun inertiaFollow(isSolved: Boolean) {}

        override fun blitterNew(width: Int, height: Int): Int {
            ops += "blitter_new:$width:$height"
            return 1
        }

        override fun blitterFree(id: Int) {
            ops += "blitter_free:$id"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save:$id:$x:$y"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load:$id:$x:$y"
        }
    }
}
