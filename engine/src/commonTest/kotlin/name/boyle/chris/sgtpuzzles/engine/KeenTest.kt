package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level tests for keen. Level: semantic where an oracle exists;
 * this file covers the paths the oracle cannot be asked about, and the
 * parameter and move grammar.
 *
 * The three-by-three description is a hand-written valid board. The
 * six-by-six description and its answer are the default golden.
 */
class KeenTest {

    private val desc3 = "_baa_3a,m6s1m3a3"
    private val aux3 = "S132321213"
    private val desc6 =
        "a_4a__a_b_aa_3aa_baa__a3b__b_3a_4,a8m12d2d2s1m108m20a7s3a5s3a11s1s2m6"
    private val aux6 = "S213645541326365214654132126453432561"

    private fun params(text: String): Keen.Params = Keen.decodeParams(text) as Keen.Params

    private fun state3(text: String = desc3, paramsText: String = "3dn"): Keen.State =
        Keen.newState(params(paramsText), text) as Keen.State

    private fun solveByMoves(start: Keen.State, aux: String): Keen.State {
        var s: Game.State = start
        for (i in 0 until start.a) {
            s = Keen.executeMove(s, "R${i % start.w},${i / start.w},${aux[i + 1]}")!!
        }
        return s as Keen.State
    }

    private fun click(x: Int, y: Int, tile: Int = 48): Pair<Int, Int> {
        val border = tile / 2
        return Pair(border + x * tile + 2, border + y * tile + 2)
    }

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `the default parameters and the ten presets are the C ones`() {
        assertEquals(Keen.Params(6, 1, false), Keen.defaultParams())
        val presets = Keen.presets()
        assertEquals(10, presets.size)
        assertEquals(
            listOf(
                "4x4 Easy" to "4de",
                "5x5 Easy" to "5de",
                "5x5 Easy, multiplication only" to "5dem",
                "6x6 Easy" to "6de",
                "6x6 Normal" to "6dn",
                "6x6 Normal, multiplication only" to "6dnm",
                "6x6 Hard" to "6dh",
                "6x6 Extreme" to "6dx",
                "6x6 Unreasonable" to "6du",
                "9x9 Normal" to "9dn",
            ),
            presets.map { it.title to it.params },
        )
    }

    @Test
    fun `decode and encode use the bare width and the d and m suffix`() {
        assertEquals(Keen.Params(6, 1, false), Keen.decodeParams("6dn"))
        assertEquals("6", Keen.encodeParams(Keen.Params(6, 1, false), false))
        assertEquals("6dn", Keen.encodeParams(Keen.Params(6, 1, false), true))
        assertEquals("6dnm", Keen.encodeParams(Keen.Params(6, 1, true), true))

        // `m` without `d`: the flag survives and the full form gains `dn`.
        assertEquals(Keen.Params(6, 1, true), Keen.decodeParams("6m"))
        assertEquals("6dnm", Keen.encodeParams(Keen.decodeParams("6m"), true))

        // Trailing junk after the recognised suffix is never read.
        assertEquals(Keen.Params(6, 1, false), Keen.decodeParams("6dnxyz"))
        assertEquals(Keen.Params(6, 0, false), Keen.decodeParams("6de"))
        assertEquals(Keen.Params(6, 0, true), Keen.decodeParams("6dem"))

        // `d` consumes the next character as the difficulty, so `6dm` is
        // an unknown difficulty and not multiplication-only.
        val dm = Keen.decodeParams("6dm") as Keen.Params
        assertEquals(6, dm.w)
        assertEquals(6, dm.diff)
        assertFalse(dm.multiplicationOnly)
        assertEquals("Unknown difficulty rating", Keen.validateParams(dm, true))

        val dOnly = Keen.decodeParams("6d") as Keen.Params
        assertEquals(6, dOnly.diff)
        assertFalse(dOnly.multiplicationOnly)
        assertEquals("6d", Keen.encodeParams(dOnly, true))

        // A bad difficulty character behaves like a missing one.
        assertEquals(Keen.encodeParams(Keen.decodeParams("6dq"), true), "6d")

        // `atoi` takes the number but the digit skip starts at the plus,
        // so no suffix is recognised after a sign.
        assertEquals(Keen.Params(5, 1, false), Keen.decodeParams("+5"))
        assertEquals(Keen.Params(6, 1, false), Keen.decodeParams(" 6dn"))
    }

    @Test
    fun `encode defines a difficulty outside 0 to 4 as a plain d`() {
        assertEquals("6d", Keen.encodeParams(Keen.Params(6, 5, false), true))
        assertEquals("6d", Keen.encodeParams(Keen.Params(6, -1, false), true))
        assertEquals("6dm", Keen.encodeParams(Keen.Params(6, 5, true), true))
        // Round trip: the short form still carries the width only.
        assertEquals("6", Keen.encodeParams(Keen.Params(6, 5, false), false))
    }

    @Test
    fun `validateParams reports the size before the difficulty`() {
        assertEquals(
            "Grid size must be between 3 and 9",
            Keen.validateParams(Keen.Params(10, 99, false), true),
        )
        assertEquals(
            "Grid size must be between 3 and 9",
            Keen.validateParams(Keen.Params(2, 1, false), true),
        )
        assertEquals(
            "Unknown difficulty rating",
            Keen.validateParams(Keen.Params(6, 5, false), true),
        )
        assertNull(Keen.validateParams(Keen.Params(3, -1, false), true))
        assertNull(Keen.validateParams(Keen.Params(9, 4, true), true))
    }

    // ------------------------------------------------------------------
    // Description validation.
    // ------------------------------------------------------------------

    @Test
    fun `validateDesc reports every C message in C order`() {
        assertNull(Keen.validateDesc(params("3dn"), desc3))
        assertNull(Keen.validateDesc(params("3dn"), "_baa_3a,m6s1m3a"))

        assertEquals(
            "Invalid character in game description",
            Keen.validateDesc(params("3dn"), "!_baa_3a,m6s1m3a3"),
        )
        assertEquals(
            "Too much data in block structure specification",
            Keen.validateDesc(params("3dn"), "z"),
        )
        assertEquals(
            "Not enough data in block structure specification",
            Keen.validateDesc(params("3dn"), ""),
        )
        assertEquals(
            "Not enough data in block structure specification",
            Keen.validateDesc(params("3dn"), "a"),
        )
        assertEquals(
            "Expected ',' after block structure description",
            Keen.validateDesc(params("3dn"), "_baa_3a"),
        )
        assertEquals(
            "Subtraction and division blocks must have area 2",
            Keen.validateDesc(params("3dn"), "_baa_3a,s6s1m3a3"),
        )
        assertEquals(
            "Subtraction and division blocks must have area 2",
            Keen.validateDesc(params("3dn"), "_baa_3a,d6s1m3a3"),
        )
        assertEquals(
            "Too few clues for block structure",
            Keen.validateDesc(params("3dn"), "_baa_3a,m6s1m3"),
        )
        assertEquals(
            "Unrecognised clue type",
            Keen.validateDesc(params("3dn"), "_baa_3a,m6s1m3x3"),
        )
        assertEquals(
            "Too many clues for block structure",
            Keen.validateDesc(params("3dn"), "_baa_3a,m6s1m3a3a1"),
        )
    }

    @Test
    fun `a clue value is not validated and a0 is accepted`() {
        assertNull(Keen.validateDesc(params("3dn"), "_baa_3a,m6s1m3a0"))
        // The solver, not the validator, rejects this board.
        val s = state3("_baa_3a,a0s1m3a3")
        assertNull(Keen.solve(s, null))
    }

    @Test
    fun `an overflowing clue value saturates instead of wrapping`() {
        val desc = "_baa_3a,m999999999999999999999999999s1m3a3"
        assertNull(Keen.validateDesc(params("3dn"), desc))
        val s = state3(desc)
        assertEquals(Long.MAX_VALUE, s.shared.clues[0])
        // The huge multiplication clue has no solution, not a crash.
        assertNull(Keen.solve(s, null))
        assertEquals(LatinSolver.DIFF_IMPOSSIBLE, Keen.solveResult(s, 4))
    }

    @Test
    fun `a huge block-structure count is too much data, not a hang`() {
        assertEquals(
            "Too much data in block structure specification",
            Keen.validateDesc(params("3dn"), "a2147483647b"),
        )
        assertEquals(
            "Too much data in block structure specification",
            Keen.validateDesc(params("3dn"), "_baa_3a" + "a2147483647b"),
        )
        // A count at the very end of the string stops with the string,
        // as C's `**p` test does, and is therefore "not enough data".
        assertEquals(
            "Not enough data in block structure specification",
            Keen.validateDesc(params("3dn"), "a2147483647"),
        )
    }

    @Test
    fun `the block-structure repeat count is equivalent to the expanded run`() {
        // `3` repeats the previous `_` three times in total, so the
        // structure is the same as `_baa___a`.
        val expanded = "_baa___a,m6s1m3a3"
        assertNull(Keen.validateDesc(params("3dn"), expanded))
        assertEquals(
            Keen.newState(params("3dn"), desc3).let { it as Keen.State }.shared,
            state3(expanded).shared,
        )

        // A count of zero repeats nothing, so `a0` is one `a`.
        val zero = "_ba0a0_3a,m6s1m3a3"
        assertNull(Keen.validateDesc(params("3dn"), zero))
        assertEquals(state3().shared, state3(zero).shared)
    }

    @Test
    fun `a three by three hard request generates a normal board`() {
        val random = RandomState.fromSeed("1")
        val generated = Keen.generateWithStats(Keen.Params(3, 2, false), random)
        assertEquals("a__c_b_,a7s1m12", generated.description.text)
        val s = Keen.newState(Keen.Params(3, 2, false), generated.description.text)
        assertEquals(1, Keen.difficulty(s))
    }

    // ------------------------------------------------------------------
    // Generation diagnostics and the solver result.
    // ------------------------------------------------------------------

    @Test
    fun `retry fixtures reach more than one attempt`() {
        val fixtures = listOf(
            "4de" to "1",
            "5de" to "1",
            "5dem" to "1",
            "6de" to "11",
            "6dn" to "4",
            "6dnm" to "9",
            "6dh" to "9",
            "6dx" to "3",
            "6du" to "4",
            "9dn" to "7",
        )
        for ((paramsText, seed) in fixtures) {
            val g = Keen.generateWithStats(
                params(paramsText),
                RandomState.fromSeed(seed),
            )
            assertTrue(
                g.stats.attempts > 1,
                "$paramsText seed $seed should retry, got ${g.stats.attempts}",
            )
            assertTrue(
                g.stats.singletonRetries + g.stats.lowerGateRejects +
                    g.stats.exactGateRejects > 0,
                "$paramsText seed $seed retried without a recorded cause",
            )
        }
    }

    @Test
    fun `the generated board has the requested difficulty`() {
        val ids = mapOf(
            "4de" to "4:__aa_4cc__aa_,d2m18d2m4s2a13",
            "5de" to "5:a_a__a_a__a_a5b_7b_,a6s1a5m20d2a10d2m6m6s1s1",
            "5dem" to "5:a3__aa_3a__a6b__aa_,m5m12m20m72m20m10m20m2m12m3",
            "6de" to "6:_b__a__a__a5_aaba_7a_3a__a__b_a_,a8m8a5m24a10s4m120d3s1d2a8m6s1m20s1s2",
            "6dn" to "6:$desc6",
            "6dnm" to "6:__aa_3a_a_4a_aa_a4baabb_3aa_a3,m12m15m5m96m144m5m4m60m18m12m120m4m5m18",
            "6dh" to "6:aa_3a_13a__aa_abaa__a_3a_ba4,a5s3m60a9d2a5s1s1a6d2m20d3s3m12d2s1m6",
            "6dx" to "6:_a_3a_4aa_4a_3aa_aa_3a_4aa_aa_c_b,s1a10a8d3m2a6d2d3s1a9d2m12m40m120s2s2d3",
            "6du" to "6:a_a_a_7a_3a_3a_a_a_4a_a7baa,d2m90m12a6s1d2m8s4s3a5m6a9d2a12s2d3",
            "9dn" to "9:__b__a_a_6a_a_4aa_4a_9a_6a__a__a_a__a_ababa3_3aa_6a3__ab__a3_a_4aa__a5,a13a12m108s3m10a9d4a5d3s3m36d2d2m40s7d3m42s2a5m48s4m21a10s2a6s5s1s3d3s2d2m15a18a13a11m40d4m72",
        )
        for ((paramsText, id) in ids) {
            val p = params(paramsText)
            val generated = Keen.generateWithStats(p, RandomState.fromSeed("12345"))
            assertEquals(Keen.encodeParams(p, false) + ":" + generated.description.text, id)
            val s = Keen.newState(p, generated.description.text)
            assertEquals(
                p.diff,
                Keen.difficulty(s),
                "difficulty of $paramsText",
            )
        }
    }

    @Test
    fun `solver errors are distinguishable and solve returns null`() {
        val impossible = state3("_baa_3a,a0s1m3a3")
        assertEquals(LatinSolver.DIFF_IMPOSSIBLE, Keen.solveResult(impossible, 4))
        assertNull(Keen.solve(impossible, null))

        val ambiguous = state3("l,a18")
        assertEquals(LatinSolver.DIFF_AMBIGUOUS, Keen.solveResult(ambiguous, 4))
        assertNull(Keen.solve(ambiguous, null))
    }

    @Test
    fun `the stored answer short-circuits the solver`() {
        val s = state3()
        assertEquals("S123456789", Keen.solve(s, "S123456789"))
    }

    @Test
    fun `the solver answer is the default golden answer`() {
        val s = Keen.newState(params("6dn"), desc6)
        assertEquals(aux6, Keen.solve(s, null))
        assertEquals(6, (s as Keen.State).w)
    }

    @Test
    fun `generation is deterministic and leaves the same random stream`() {
        val a = RandomState.fromSeed("stream")
        val b = RandomState.fromSeed("stream")
        val first = Keen.newDesc(params("6dn"), a)
        val second = Keen.newDesc(params("6dn"), b)
        assertEquals(first, second)
        assertEquals(a.encodeState(), b.encodeState())
    }

    // ------------------------------------------------------------------
    // New state and moves.
    // ------------------------------------------------------------------

    @Test
    fun `newState starts empty and keeps the block partition`() {
        val s = state3()
        assertEquals(9, s.grid.size)
        assertTrue(s.grid.all { it == 0 })
        assertTrue(s.pencil.all { it == 0 })
        assertFalse(s.completed)
        assertFalse(s.cheated)
        var blocks = 0
        for (i in 0 until 9) {
            if (s.shared.dsf.minimal(i) == i) blocks++
            assertTrue(s.shared.dsf.minimal(i) <= i)
        }
        assertEquals(4, blocks)
        assertEquals(3, s.shared.dsf.size(0))
    }

    @Test
    fun `interpretMove handles the Android highlight branches`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui

        val (x0, y0) = click(0, 0)
        assertEquals(MoveResult.UiUpdate, Keen.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48))
        assertTrue(u.hshow)
        assertFalse(u.hpencil)
        assertFalse(u.hcursor)
        assertEquals(0, u.hx)
        assertEquals(0, u.hy)

        // A second left click on the same cell toggles pencil mode and
        // keeps the highlight.
        assertEquals(MoveResult.UiUpdate, Keen.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48))
        assertTrue(u.hshow)
        assertTrue(u.hpencil)
        assertEquals(0, u.hx)

        // A left click on another cell keeps `hpencil` on Android.
        val (x1, y1) = click(1, 0)
        assertEquals(MoveResult.UiUpdate, Keen.interpretMove(s, u, x1, y1, Buttons.LEFT_BUTTON, 48))
        assertEquals(1, u.hx)
        assertTrue(u.hpencil)

        // A right click on an empty cell starts a pencil highlight; a
        // second right click on it hides the highlight.
        val u2 = Keen.newUi(s) as Keen.Ui
        val (x2, y2) = click(2, 0)
        assertEquals(MoveResult.UiUpdate, Keen.interpretMove(s, u2, x2, y2, Buttons.RIGHT_BUTTON, 48))
        assertTrue(u2.hshow)
        assertTrue(u2.hpencil)
        assertEquals(2, u2.hx)
        assertEquals(MoveResult.UiUpdate, Keen.interpretMove(s, u2, x2, y2, Buttons.RIGHT_BUTTON, 48))
        assertFalse(u2.hshow)
        assertFalse(u2.hpencil)

        // A right click on a filled cell hides the highlight.
        val filled = Keen.executeMove(s, "R0,0,1") as Keen.State
        val u3 = Keen.newUi(filled) as Keen.Ui
        assertEquals(MoveResult.UiUpdate, Keen.interpretMove(filled, u3, x0, y0, Buttons.RIGHT_BUTTON, 48))
        assertFalse(u3.hshow)
        assertFalse(u3.hpencil)

        // An outside click clears both flags.
        val u4 = Keen.newUi(s) as Keen.Ui
        Keen.interpretMove(s, u4, x1, y1, Buttons.LEFT_BUTTON, 48)
        assertTrue(u4.hshow)
        assertEquals(
            MoveResult.UiUpdate,
            Keen.interpretMove(s, u4, 5, 5, Buttons.LEFT_BUTTON, 48),
        )
        assertFalse(u4.hshow)
        assertFalse(u4.hpencil)
    }

    @Test
    fun `the cursor clamps and its result is the C one`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui

        assertEquals(
            MoveResult.UiUpdate,
            Keen.interpretMove(s, u, 0, 0, Buttons.CURSOR_UP, 48),
        )
        assertTrue(u.hshow)
        assertTrue(u.hcursor)
        assertEquals(0, u.hx)
        assertEquals(0, u.hy)

        // Clamped at the edge with a visible cursor: valid but no effect.
        assertEquals(
            MoveResult.NoEffect,
            Keen.interpretMove(s, u, 0, 0, Buttons.CURSOR_UP, 48),
        )

        assertEquals(
            MoveResult.UiUpdate,
            Keen.interpretMove(s, u, 0, 0, Buttons.CURSOR_RIGHT, 48),
        )
        assertEquals(1, u.hx)

        // A modified arrow still moves: the game strips the modifier.
        assertEquals(
            MoveResult.UiUpdate,
            Keen.interpretMove(
                s,
                u,
                0,
                0,
                Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT,
                48,
            ),
        )
        assertEquals(2, u.hx)

        // CURSOR_SELECT toggles pencil mode.
        assertEquals(
            MoveResult.UiUpdate,
            Keen.interpretMove(s, u, 0, 0, Buttons.CURSOR_SELECT, 48),
        )
        assertTrue(u.hpencil)
        assertTrue(u.hcursor)
    }

    @Test
    fun `digit entry, pencil entry, clear and mark follow the C strings`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui
        val (x0, y0) = click(0, 0)
        Keen.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48)

        assertEquals(
            MoveResult.Move("R0,0,3"),
            Keen.interpretMove(s, u, 0, 0, '3'.code, 48),
        )

        // A clear of an already empty cell is unused with the default
        // preference; to get a clear move, fill the cell first.
        val filled = Keen.executeMove(s, "R0,0,3") as Keen.State
        val uf = Keen.newUi(filled) as Keen.Ui
        Keen.interpretMove(filled, uf, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Move("R0,0,0"),
            Keen.interpretMove(filled, uf, 0, 0, Buttons.CURSOR_SELECT2, 48),
        )
        assertEquals(
            MoveResult.Move("R0,0,0"),
            Keen.interpretMove(filled, uf, 0, 0, '\b'.code, 48),
        )

        // A pencil highlight makes a positive digit a `P` move.
        u.hpencil = true
        assertEquals(
            MoveResult.Move("P0,0,3"),
            Keen.interpretMove(s, u, 0, 0, '3'.code, 48),
        )
        assertEquals(
            MoveResult.Unused,
            Keen.interpretMove(s, u, 0, 0, Buttons.CURSOR_SELECT2, 48),
        )

        // Mark works without a highlight, and both cases of the letter.
        val hidden = Keen.newUi(s) as Keen.Ui
        assertEquals(MoveResult.Move("M"), Keen.interpretMove(s, hidden, 0, 0, 'M'.code, 48))
        assertEquals(MoveResult.Move("M"), Keen.interpretMove(s, hidden, 0, 0, 'm'.code, 48))
    }

    @Test
    fun `a rejected pencil mark and a same-digit entry are unused`() {
        val s = state3()
        val filled = Keen.executeMove(s, "R0,0,3") as Keen.State

        // A pencil highlight on a filled cell is only reachable through
        // the cursor keys.
        val u = Keen.newUi(filled) as Keen.Ui
        Keen.interpretMove(filled, u, 0, 0, Buttons.CURSOR_UP, 48)
        Keen.interpretMove(filled, u, 0, 0, Buttons.CURSOR_SELECT, 48)
        assertTrue(u.hpencil)
        assertEquals(
            MoveResult.Unused,
            Keen.interpretMove(filled, u, 0, 0, '3'.code, 48),
        )

        // The same digit with an empty pencil bitmap is unused while the
        // preference keeps the highlight.
        val u2 = Keen.newUi(filled) as Keen.Ui
        val (x0, y0) = click(0, 0)
        Keen.interpretMove(filled, u2, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Unused,
            Keen.interpretMove(filled, u2, 0, 0, '3'.code, 48),
        )
        assertTrue(u2.hshow)
    }

    @Test
    fun `the pencil-keep-highlight preference hides a mouse highlight`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui
        u.pencilKeepHighlight = false
        val (x0, y0) = click(0, 0)
        Keen.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Move("R0,0,3"),
            Keen.interpretMove(s, u, 0, 0, '3'.code, 48),
        )
        assertFalse(u.hshow)

        val u2 = Keen.newUi(s) as Keen.Ui
        u2.pencilKeepHighlight = false
        Keen.interpretMove(s, u2, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.UiUpdate,
            Keen.interpretMove(s, u2, 0, 0, '0'.code, 48),
        )
        assertFalse(u2.hshow)

        // With the default true neither case hides it.
        val u3 = Keen.newUi(s) as Keen.Ui
        Keen.interpretMove(s, u3, x0, y0, Buttons.LEFT_BUTTON, 48)
        Keen.interpretMove(s, u3, 0, 0, '0'.code, 48)
        assertTrue(u3.hshow)
    }

    @Test
    fun `drag, release and middle press are unused`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui
        val (x0, y0) = click(0, 0)
        for (button in listOf(
            Buttons.LEFT_DRAG,
            Buttons.RIGHT_DRAG,
            Buttons.LEFT_RELEASE,
            Buttons.RIGHT_RELEASE,
            Buttons.MIDDLE_BUTTON,
        )) {
            assertEquals(
                MoveResult.Unused,
                Keen.interpretMove(s, u, x0, y0, button, 48),
                "button $button",
            )
        }
        assertFalse(u.hshow)
    }

    @Test
    fun `executeMove applies R, P, M and S`() {
        val s = state3()

        val r = Keen.executeMove(s, "R0,0,3") as Keen.State
        assertEquals(3, r.grid[0])
        assertEquals(0, r.pencil[0])
        assertFalse(r.completed)

        val p = Keen.executeMove(s, "P0,0,3") as Keen.State
        assertEquals(0, p.grid[0])
        assertEquals(1 shl 3, p.pencil[0])

        val toggle = Keen.executeMove(p, "P0,0,3") as Keen.State
        assertEquals(0, toggle.pencil[0])

        // P on a filled cell is accepted by C.
        val under = Keen.executeMove(r, "P0,0,3") as Keen.State
        assertEquals(3, under.grid[0])
        assertEquals(1 shl 3, under.pencil[0])

        // P0,0,0 takes the R branch and clears the cell.
        val cleared = Keen.executeMove(r, "P0,0,0") as Keen.State
        assertEquals(0, cleared.grid[0])
        assertEquals(0, cleared.pencil[0])

        val marked = Keen.executeMove(s, "M") as Keen.State
        for (i in 0 until 9) assertEquals((1 shl 4) - (1 shl 1), marked.pencil[i])
        assertFalse(marked.completed)

        val solved = Keen.executeMove(s, aux3) as Keen.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertTrue(solved.pencil.all { it == 0 })
    }

    @Test
    fun `executeMove rejects every invalid form`() {
        val s = state3()
        val bad = listOf(
            "",
            "X0,0,1",
            "S",
            "S123",
            "S12345678",
            "S1234567890",
            "S123456780",
            "S023456789",
            "Sab2345678",
            "R3,0,1",
            "R0,3,1",
            "R-1,0,1",
            "R0,-1,1",
            "R0,0,4",
            "R0,0,-1",
            "R0",
            "R0,0",
            "R1.5,2,3",
            "R18446744073709551616,0,1",
        )
        for (move in bad) {
            assertNull(Keen.executeMove(s, move), "move '$move'")
        }
    }

    @Test
    fun `executeMove accepts the sscanf quirks`() {
        val s = state3()
        for (move in listOf("R 1,2,3", "R+1,2,3", "R1,2,3junk", "R1,2,3,4")) {
            val r = Keen.executeMove(s, move)
            assertNotNull(r, "move '$move'")
            assertEquals(3, (r as Keen.State).grid[2 * 3 + 1], "move '$move'")
        }
        // Whitespace is skipped after a comma too.
        val spaced = Keen.executeMove(s, "R1, 2, 3") as Keen.State
        assertEquals(3, spaced.grid[7])
    }

    @Test
    fun `completion is sticky and status follows it`() {
        var s: Game.State = state3()
        for (i in 0 until 9) {
            val x = i % 3
            val y = i / 3
            s = Keen.executeMove(s, "R$x,$y,${aux3[i + 1]}")!!
        }
        assertTrue((s as Keen.State).completed)
        assertEquals(1, Keen.status(s))

        // Break the board: the flag stays set.
        val broken = Keen.executeMove(s, "R0,0,1") as Keen.State
        assertTrue(broken.completed)
        assertEquals(1, Keen.status(broken))

        val fresh = state3()
        assertEquals(0, Keen.status(fresh))
        assertFalse(fresh.completed)
    }

    @Test
    fun `checkErrors marks the clue and the duplicate digits`() {
        val empty = state3()
        val errors = IntArray(9)
        assertTrue(Keen.checkErrors(empty, errors))
        assertTrue(errors.all { it == 0 })

        val solved = Keen.executeMove(empty, aux3) as Keen.State
        errors.fill(0)
        assertFalse(Keen.checkErrors(solved, errors))
        assertTrue(errors.all { it == 0 })

        // Two equal digits in a row are both marked as Latin errors.
        val duplicate = empty.copy() as Keen.State
        duplicate.grid[0] = 3
        duplicate.grid[1] = 3
        errors.fill(0)
        assertTrue(Keen.checkErrors(duplicate, errors))
        assertTrue(errors[0] and 0x8000 != 0)
        assertTrue(errors[1] and 0x8000 != 0)
        assertTrue(errors[0] and 0x4000 == 0)

        // A complete block with a wrong value marks the clue.
        val wrong = solved.copy() as Keen.State
        val min = wrong.shared.dsf.minimal(0)
        wrong.grid[min] = if (wrong.grid[min] == 1) 2 else 1
        errors.fill(0)
        assertTrue(Keen.checkErrors(wrong, errors))
        assertTrue(errors[min] and 0x4000 != 0)
    }

    @Test
    fun `changedState cancels a pencil highlight and reports completion`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui
        val (x0, y0) = click(0, 0)
        Keen.interpretMove(s, u, x0, y0, Buttons.RIGHT_BUTTON, 48)
        assertTrue(u.hshow && u.hpencil)

        val filled = Keen.executeMove(s, "R0,0,1") as Keen.State
        assertFalse(Keen.changedState(u, s, filled))
        assertFalse(u.hshow)
        assertFalse(u.hpencil)

        val solved = solveByMoves(s, aux3)
        assertTrue(solved.completed)
        assertFalse(solved.cheated)
        assertTrue(Keen.changedState(Keen.newUi(s), s, solved))
        assertFalse(Keen.changedState(Keen.newUi(solved), solved, solved))
        val cheated = Keen.executeMove(s, aux3) as Keen.State
        assertFalse(Keen.changedState(Keen.newUi(s), s, cheated))
        assertFalse(Keen.changedState(Keen.newUi(s), null, solved))
    }

    @Test
    fun `undo and redo across a real move and a solve`() {
        val md = Midend.newGame(Keen, "3dn", "1")
        assertFalse(md.canUndo)
        assertEquals(0, md.status())

        val move = md.processKey(0, 0, Buttons.CURSOR_UP, 48)
        assertEquals(KeyOutcome.SomeEffect, move)
        assertFalse(md.canUndo)
        assertEquals(
            KeyOutcome.SomeEffect,
            md.processKey(0, 0, '1'.code, 48),
        )
        assertTrue(md.canUndo)
        assertEquals(2, md.stateCount)

        assertTrue(md.undo())
        assertEquals(2, md.stateCount)
        assertEquals(0, md.status())
        assertTrue(md.canRedo)
        assertTrue(md.redo())
        assertEquals(0, md.status())

        assertTrue(md.solve())
        assertEquals(1, md.status())
        assertTrue(md.canUndo)
        assertTrue(md.undo())
        assertEquals(0, md.status())
    }

    @Test
    fun `a real completion raises one event and undo and redo cross it`() {
        val md = Midend.newGame(Keen, "3dn", "1")
        assertNotNull(md.auxInfo)
        val aux = md.auxInfo!!
        for (i in 0 until 9) {
            val x = i % 3
            val y = i / 3
            val (px, py) = click(x, y)
            md.processKey(px, py, Buttons.LEFT_BUTTON, 48)
            md.processKey(0, 0, aux[i + 1].code, 48)
        }
        assertEquals(1, md.status())
        assertTrue(md.takeCompletedEvent())
        assertFalse(md.takeCompletedEvent())

        assertTrue(md.undo())
        assertEquals(0, md.status())
        assertFalse(md.takeCompletedEvent())
        assertTrue(md.redo())
        assertEquals(1, md.status())
        assertTrue(md.takeCompletedEvent())
    }

    @Test
    fun `text, status, flags and the key request are the C values`() {
        val s = state3()
        assertFalse(Keen.canFormatAsText)
        assertEquals("", Keen.textFormat(s))
        assertEquals("", Keen.statusText(s, Keen.newUi(s)))
        assertFalse(Keen.wantsStatusBar)
        assertTrue(Keen.canSolve)
        assertEquals(0xC00, Keen.flags)
        assertEquals(48, Keen.preferredTileSize)

        for (p in Keen.presets()) {
            val request = Keen.requestKeys(params(p.params))
            assertEquals(ArrowMode.ARROWS_LEFT, request.arrowMode)
            val w = params(p.params).w
            assertEquals(w + 2, request.labels.size)
            for (i in 0 until w) {
                assertEquals('1'.code + i, request.labels[i].button)
                assertEquals("${i + 1}", request.labels[i].name)
                assertFalse(request.labels[i].needsArrows)
            }
            assertEquals('\b'.code, request.labels[w].button)
            assertEquals("Clear", request.labels[w].name)
            assertEquals('M'.code, request.labels[w + 1].button)
            assertEquals("Mark", request.labels[w + 1].name)
            assertEquals(request, Keen.requestKeys(params(p.params), true))
        }
    }

    @Test
    fun `currentKeyLabel follows the highlight and the pencil flag`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui
        assertEquals("", Keen.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("", Keen.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))

        u.hshow = true
        assertEquals("Pencil", Keen.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("", Keen.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        u.hpencil = true
        assertEquals("Ink", Keen.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `ui starts hidden with the preference on and a fresh load keeps it`() {
        val md = Midend.newGame(Keen, "3dn", "1")
        val u = md.ui as Keen.Ui
        assertFalse(u.hshow)
        assertTrue(u.pencilKeepHighlight)

        val loaded = Midend.fromSave(Keen, md.serialise())
        val lu = loaded.ui as Keen.Ui
        assertFalse(lu.hshow)
        assertTrue(lu.pencilKeepHighlight)
        assertNull(Keen.encodeUi(lu))
    }

    @Test
    fun `cursorLocation uses the C rectangle`() {
        val s = state3()
        val u = Keen.newUi(s) as Keen.Ui
        assertNull(Keen.cursorLocation(u, s, 48))
        u.hshow = true
        u.hx = 1
        u.hy = 2
        assertEquals(Game.CursorRect(74, 122, 45, 45), Keen.cursorLocation(u, s, 48))
        u.hx = 0
        u.hy = 0
        assertEquals(Game.CursorRect(26, 26, 45, 45), Keen.cursorLocation(u, s, 48))
    }

    @Test
    fun `computeSize and the palette follow the C arithmetic`() {
        assertEquals(Size(336, 336), Keen.computeSize(params("6dn"), 48))
        assertEquals(Size(440, 440), Keen.computeSize(params("6dn"), 63))
        assertEquals(Size(240, 240), Keen.computeSize(params("4de"), 48))
        assertEquals(Size(574, 574), Keen.computeSize(params("6dn"), 82))
        assertEquals(Size(700, 700), Keen.computeSize(params("6dn"), 100))
        // An odd tile loses one pixel to integer `T/2`.
        assertEquals(Size(7 * 63 - 1, 7 * 63 - 1), Keen.computeSize(params("6dn"), 63))

        val palette = Keen.colours()
        assertEquals(21, palette.size)
        assertEquals(0.8f, palette[0])
        assertEquals(0.8f, palette[1])
        assertEquals(0.8f, palette[2])
        assertEquals(0.0f, palette[6])
        assertEquals(0.0f, palette[8])
        assertEquals(0.0f, palette[9])
        assertEquals(0.48f, palette[10], 1e-6f)
        assertEquals(0.624f, palette[12], 1e-6f)
        assertEquals(1.0f, palette[15], 1e-6f)
        assertEquals(0.4f, palette[18], 1e-6f)
        assertEquals(0.8f, palette[20], 1e-6f)

        // A non-default background exercises every derived channel.
        val custom = Keen.colours(floatArrayOf(0.5f, 0.25f, 0.75f))
        assertEquals(0.5f, custom[0], 1e-6f)
        assertEquals(0.25f, custom[1], 1e-6f)
        assertEquals(0.75f, custom[2], 1e-6f)
        assertEquals(0.15f, custom[10], 1e-6f)
        assertEquals(0.39f, custom[12], 1e-6f)
        assertEquals(0.195f, custom[13], 1e-6f)
        assertEquals(0.585f, custom[14], 1e-6f)
        assertEquals(0.25f, custom[18], 1e-6f)
        assertEquals(0.125f, custom[19], 1e-6f)
        assertEquals(0.75f, custom[20], 1e-6f)
    }
}
