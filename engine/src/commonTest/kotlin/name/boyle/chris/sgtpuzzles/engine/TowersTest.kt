package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Engine-level tests for towers. Level: semantic where an oracle exists;
 * this file covers the paths the oracle cannot be asked about, the
 * parameter and move grammar, and the generation diagnostics.
 *
 * The 3x3 board is 12 empty clue fields, so every cell is mutable. The
 * 5x5 descriptions are the default golden and its stored answer.
 */
class TowersTest {

    private val desc5 = "3/2/2/2/1/2/4/3/1/2/5/2/3/1/2/1/2/3/3/2"
    private val aux5 = "S1234545123245315341231254"
    private val desc5h = "//2////4/2//2/////1/2/1///,h2p"

    private fun emptyDesc(w: Int): String = "/".repeat(4 * w - 1)

    private fun params(text: String): Towers.Params = Towers.decodeParams(text) as Towers.Params

    private fun state(desc: String, paramsText: String = "3de"): Towers.State =
        Towers.newState(params(paramsText), desc) as Towers.State

    private fun click(x: Int, y: Int, tile: Int = 48): Pair<Int, Int> {
        val border = tile * 9 / 8
        return Pair(border + x * tile + 2, border + y * tile + 2)
    }

    private fun center(x: Int, y: Int, tile: Int = 48): Pair<Int, Int> {
        val border = tile * 9 / 8
        return Pair(border + x * tile + tile / 2, border + y * tile + tile / 2)
    }

    private fun solveByMoves(start: Towers.State, aux: String): Towers.State {
        var s: Game.State = start
        for (i in 0 until start.a) {
            s = Towers.executeMove(
                s,
                "R${i % start.w},${i / start.w},${aux[i + 1]}",
            )!!
        }
        return s as Towers.State
    }

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `the default parameters and the seven presets are the C ones`() {
        assertEquals(Towers.Params(5, 0), Towers.defaultParams())
        assertEquals(
            listOf(
                "4x4 Easy" to "4de",
                "5x5 Easy" to "5de",
                "5x5 Hard" to "5dh",
                "6x6 Easy" to "6de",
                "6x6 Hard" to "6dh",
                "6x6 Extreme" to "6dx",
                "6x6 Unreasonable" to "6du",
            ),
            Towers.presets().map { it.title to it.params },
        )
    }

    @Test
    fun `decode and encode use the bare width and the d suffix`() {
        assertEquals(Towers.Params(5, 0), Towers.decodeParams("5de"))
        assertEquals(Towers.Params(5, 1), Towers.decodeParams("5dh"))
        assertEquals(Towers.Params(5, 2), Towers.decodeParams("5dx"))
        assertEquals(Towers.Params(5, 3), Towers.decodeParams("5du"))
        assertEquals("5", Towers.encodeParams(Towers.Params(5, 0), false))
        assertEquals("5de", Towers.encodeParams(Towers.Params(5, 0), true))
        assertEquals("5du", Towers.encodeParams(Towers.Params(5, 3), true))

        // A string without `d` keeps the default difficulty.
        assertEquals(Towers.Params(5, 0), Towers.decodeParams("5"))
        // Trailing junk after a recognised suffix is never read.
        assertEquals(Towers.Params(5, 1), Towers.decodeParams("5dhx"))
        // `atoi` takes the number but the digit skip starts at the first
        // character, so no suffix is recognised after a sign or space.
        assertEquals(Towers.Params(5, 0), Towers.decodeParams("+5"))
        assertEquals(Towers.Params(5, 0), Towers.decodeParams("05"))
        assertEquals(Towers.Params(5, 0), Towers.decodeParams(" 5"))
        assertEquals(Towers.Params(5, 0), Towers.decodeParams("5de "))

        // `d` with no character is invalid, and an unknown character is
        // invalid too. Both encode as a plain `d`.
        val dOnly = Towers.decodeParams("5d") as Towers.Params
        assertEquals(5, dOnly.w)
        assertEquals(5, dOnly.diff)
        assertEquals("Unknown difficulty rating", Towers.validateParams(dOnly, true))
        assertEquals("5d", Towers.encodeParams(dOnly, true))
        assertEquals(Towers.encodeParams(Towers.decodeParams("5dq"), true), "5d")
    }

    @Test
    fun `encode defines a difficulty outside 0 to 3 as a plain d`() {
        assertEquals("5d", Towers.encodeParams(Towers.Params(5, 4), true))
        assertEquals("5d", Towers.encodeParams(Towers.Params(5, -1), true))
        assertEquals("5", Towers.encodeParams(Towers.Params(5, 4), false))
    }

    @Test
    fun `validateParams reports the size before the difficulty`() {
        assertEquals(
            "Grid size must be between 3 and 9",
            Towers.validateParams(Towers.Params(10, 99), true),
        )
        assertEquals(
            "Grid size must be between 3 and 9",
            Towers.validateParams(Towers.Params(2, 0), true),
        )
        assertEquals(
            "Unknown difficulty rating",
            Towers.validateParams(Towers.Params(3, 4), true),
        )
        assertNull(Towers.validateParams(Towers.Params(3, 0), true))
        assertNull(Towers.validateParams(Towers.Params(9, 3), true))
        assertNull(Towers.validateParams(Towers.Params(3, -1), true))
    }

    // ------------------------------------------------------------------
    // Description validation.
    // ------------------------------------------------------------------

    @Test
    fun `validateDesc reports every C message in C order`() {
        assertNull(Towers.validateDesc(params("3de"), emptyDesc(3)))
        assertNull(Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1/1"))
        assertNull(Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1/"))

        assertEquals(
            "Too few clues for grid size",
            Towers.validateDesc(params("3de"), "1/1"),
        )
        assertEquals(
            "Expected commas between clues",
            Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1x"),
        )
        assertEquals(
            "Clue number out of range",
            Towers.validateDesc(params("3de"), "4/1/1/1/1/1/1/1/1/1/1/1"),
        )
        assertEquals(
            "Too many clues for grid size",
            Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1/1/"),
        )
        assertEquals(
            "Not enough data to fill grid",
            Towers.validateDesc(params("3de"), "${emptyDesc(3)},aaaaaaaa"),
        )
        assertEquals(
            "Too much data to fit in grid",
            Towers.validateDesc(params("3de"), "${emptyDesc(3)},aaaaaaaaaa"),
        )
        assertEquals(
            "Invalid character in game description",
            Towers.validateDesc(params("3de"), "${emptyDesc(3)},aaaaaaaa0"),
        )
        assertEquals(
            "Out-of-range number in grid description",
            Towers.validateDesc(params("3de"), "${emptyDesc(3)},11aaaaaaa"),
        )
        assertEquals(
            "Rubbish at end of game description",
            Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1/1x"),
        )
    }

    @Test
    fun `validateDesc accepts the description quirks`() {
        // No grid part loads as an all-empty board.
        assertNull(Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1/1"))
        // A field is validated only when it starts with a digit; an
        // empty final field is legal.
        assertNull(Towers.validateDesc(params("3de"), "1/1/1/1/1/1/1/1/1/1/1/"))
        // `_` counts zero squares and is accepted anywhere.
        assertNull(Towers.validateDesc(params("3de"), "${emptyDesc(3)},_1aaaaaaaa"))
        assertNull(Towers.validateDesc(params("3de"), "${emptyDesc(3)},a_1aaaaaaa"))
        assertNull(Towers.validateDesc(params("3de"), "${emptyDesc(3)},1_1aaaaaaa"))
    }

    @Test
    fun `validateDesc rejects and newState refuses a trailing comma`() {
        assertEquals(
            "Not enough data to fill grid",
            Towers.validateDesc(params("3de"), "${emptyDesc(3)},"),
        )
        var threw = false
        try {
            Towers.newState(params("3de"), "${emptyDesc(3)},")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ------------------------------------------------------------------
    // Generation.
    // ------------------------------------------------------------------

    @Test(timeout = 600_000)
    fun `the eight golden descriptions match`() {
        val golden = mapOf(
            "" to "5:3/2/2/2/1/2/4/3/1/2/5/2/3/1/2/1/2/3/3/2",
            "4de" to "4:1/2/2/3/3/2/2/1/1/2/3/3/3/3/2/1",
            "5de" to "5:3/2/2/2/1/2/4/3/1/2/5/2/3/1/2/1/2/3/3/2",
            "5dh" to "5://2////4/2//2/////1/2/1///,h2p",
            "6de" to "6:2/3/3/1/4/2/4/2/3/3/2/1/4/1/2/2/4/3/2/5/3/3/2/1,p1d2_5b1j",
            "6dh" to "6:/3/3//4////3///1/4////4/3//5////,p1d2n",
            "6du" to "6:1/3//3/2//3///2/////4//3///3/2///2,n1f3n",
            "6dx" to "6:2/3//2/3/1//3/3/2///3///2/2/4////3/2/,v1m",
        )
        for ((paramsText, id) in golden) {
            val p = if (paramsText.isEmpty()) Towers.defaultParams() as Towers.Params
            else params(paramsText)
            val generated = Towers.generateWithStats(p, RandomState.fromSeed("12345"))
            assertEquals(
                Towers.encodeParams(p, false) + ":" + generated.description.text,
                id,
                "golden '$paramsText'",
            )
            assertTrue(generated.description.auxInfo!!.startsWith("S"))
            assertEquals(p.w * p.w + 1, generated.description.auxInfo!!.length)
            // The generated board grades as the requested level. The
            // empty params case is the default Easy board.
            val s = Towers.newState(p, generated.description.text)
            assertEquals(p.diff, Towers.difficulty(s), "difficulty of '$paramsText'")
        }
    }

    @Test(timeout = 600_000)
    fun `retry fixtures reach more than one attempt`() {
        // Every preset except 6de has a seed that retries. 6de cannot
        // retry: its w > 5, so it has no empty-grid pre-check, and every
        // accepted cell removal was solvable at level 0, so the final
        // gate on the same grid is also level 0. Pin that fact below.
        val fixtures = listOf(
            "4de" to "6",
            "5de" to "1",
            "5dh" to "16",
            "6dh" to "535",
            "6dx" to "0",
            "6du" to "1",
        )
        for ((paramsText, seed) in fixtures) {
            val g = Towers.generateWithStats(
                params(paramsText),
                RandomState.fromSeed(seed),
            )
            assertTrue(
                g.stats.attempts > 1,
                "$paramsText seed $seed should retry, got ${g.stats.attempts}",
            )
            assertTrue(
                g.stats.emptyGridRejects + g.stats.exactGateRejects > 0,
                "$paramsText seed $seed retried without a recorded cause",
            )
            if (paramsText.endsWith("de")) {
                assertTrue(
                    g.stats.emptyGridRejects > 0,
                    "$paramsText seed $seed should hit the empty-grid pre-check",
                )
                assertTrue(
                    g.stats.exactGateRejects == 0,
                    "$paramsText seed $seed cannot fail the final gate",
                )
            }
        }

        // 6de seed 1 is one attempt, and the two retry causes are zero.
        val sixEasy = Towers.generateWithStats(
            params("6de"),
            RandomState.fromSeed("1"),
        )
        assertEquals(1, sixEasy.stats.attempts)
        assertEquals(0, sixEasy.stats.emptyGridRejects)
        assertEquals(0, sixEasy.stats.exactGateRejects)
    }

    @Test(timeout = 600_000)
    fun `the three by three clamp turns Extreme and Unreasonable into Hard`() {
        val hard = Towers.generateWithStats(
            Towers.Params(3, 1),
            RandomState.fromSeed("7"),
        )
        for (diff in listOf(2, 3)) {
            val g = Towers.generateWithStats(
                Towers.Params(3, diff),
                RandomState.fromSeed("7"),
            )
            assertEquals(hard.description, g.description, "difficulty $diff")
        }
        assertEquals("2/////2///1///", hard.description.text)
    }

    @Test(timeout = 300_000)
    fun `generation is deterministic and leaves the same random stream`() {
        val a = RandomState.fromSeed("stream")
        val b = RandomState.fromSeed("stream")
        val first = Towers.newDesc(params("6dh"), a)
        val second = Towers.newDesc(params("6dh"), b)
        assertEquals(first, second)
        assertEquals(a.encodeState(), b.encodeState())
    }

    @Test(timeout = 300_000)
    fun `AUXINFO is the full solution of the accepted attempt`() {
        val p = params("6du")
        val generated = Towers.generateWithStats(p, RandomState.fromSeed("3"))
        val aux = requireNotNull(generated.description.auxInfo)
        val s = Towers.newState(p, generated.description.text)
        // The stored answer solves the board at every level up to
        // Unreasonable, and applying it leaves no error.
        assertEquals(aux, Towers.solve(s, aux))
        val solved = Towers.executeMove(s, aux) as Towers.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertFalse(Towers.checkErrors(solved, null))
    }

    @Test
    fun `the five Latin solver slots are the C values`() {
        assertContentEquals(intArrayOf(0, 1, 2, 2, 3), Towers.solverSlots())
    }

    // ------------------------------------------------------------------
    // State and moves.
    // ------------------------------------------------------------------

    @Test
    fun `newState is empty and the given cells are immutable`() {
        val s = state(emptyDesc(3))
        assertTrue(s.grid.all { it == 0 })
        assertTrue(s.pencil.all { it == 0 })
        assertTrue(s.cluesDone.all { !it })
        assertTrue(s.shared.clues.all { it == 0 })
        assertTrue(s.shared.immutable.all { it == 0 })
        assertFalse(s.completed)
        assertFalse(s.cheated)

        val given = state("${emptyDesc(3)},1aaaaaaaa")
        assertEquals(1, given.grid[0])
        assertEquals(1, given.shared.immutable[0])
        assertEquals(0, given.grid[1])
        // C treats a given cell as a lock as well as a value.
        assertNull(Towers.executeMove(given, "R0,0,2"))
        assertNull(Towers.executeMove(given, "P0,0,2"))
    }

    @Test
    fun `a copied state shares the clues and copies the mutable arrays`() {
        val s = state(emptyDesc(3))
        val moved = Towers.executeMove(s, "R0,0,1") as Towers.State
        val copy = moved.copy() as Towers.State
        assertSame(moved.shared, copy.shared)
        assertSame(s.shared, copy.shared)
        moved.grid[1] = 2
        assertEquals(0, copy.grid[1])
        copy.cluesDone[0] = true
        assertFalse(moved.cluesDone[0])
    }

    @Test
    fun `interpretMove handles the Android highlight branches`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        val (x0, y0) = click(0, 0)

        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(u.hshow)
        assertFalse(u.hpencil)
        assertFalse(u.hcursor)

        // A second left click on the same cell toggles pencil mode and
        // keeps the highlight.
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(u.hshow)
        assertTrue(u.hpencil)

        // A left click on another cell keeps `hpencil` on Android.
        val (x1, y1) = click(1, 0)
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, x1, y1, Buttons.LEFT_BUTTON, 48),
        )
        assertEquals(1, u.hx)
        assertTrue(u.hpencil)

        // A right click on an empty cell starts a pencil highlight; a
        // second right click on it clears both flags.
        val u2 = Towers.newUi(s) as Towers.Ui
        val (x2, y2) = click(2, 0)
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u2, x2, y2, Buttons.RIGHT_BUTTON, 48),
        )
        assertTrue(u2.hshow && u2.hpencil)
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u2, x2, y2, Buttons.RIGHT_BUTTON, 48),
        )
        assertFalse(u2.hshow)
        assertFalse(u2.hpencil)

        // A right click on a filled cell clears both flags.
        val filled = Towers.executeMove(s, "R0,0,1") as Towers.State
        val u3 = Towers.newUi(filled) as Towers.Ui
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(filled, u3, x0, y0, Buttons.RIGHT_BUTTON, 48),
        )
        assertFalse(u3.hshow)
        assertFalse(u3.hpencil)

        // A left click on a given moves the highlight there and hides it.
        val given = state("${emptyDesc(3)},1aaaaaaaa")
        val u4 = Towers.newUi(given) as Towers.Ui
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(given, u4, x0, y0, Buttons.LEFT_BUTTON, 48),
        )
        assertEquals(0, u4.hx)
        assertFalse(u4.hshow)

        // An outside click clears both flags.
        val u5 = Towers.newUi(s) as Towers.Ui
        Towers.interpretMove(s, u5, x1, y1, Buttons.LEFT_BUTTON, 48)
        assertTrue(u5.hshow)
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u5, 0, 0, Buttons.LEFT_BUTTON, 48),
        )
        assertFalse(u5.hshow)
        assertFalse(u5.hpencil)
    }

    @Test
    fun `the 3D hit test selects the tower and the last candidate wins`() {
        val s = state(emptyDesc(3))
        s.grid[0] = 3
        val u = Towers.newUi(s) as Towers.Ui

        // (90,45) is geometrically above the grid, but it is inside the
        // top face of the height-3 tower at (0,0).
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, 90, 45, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(u.hshow)
        assertEquals(0, u.hx)
        assertEquals(0, u.hy)
        assertEquals(Game.CursorRect(54, 54, 48, 48), Towers.cursorLocation(u, s, 48))

        // In 2D the same click is outside the grid and clears it.
        u.threeD = false
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, 90, 45, Buttons.LEFT_BUTTON, 48),
        )
        assertFalse(u.hshow)

        // Two overlapping top faces: the click is in both, and the loop
        // has no break, so the later candidate (0,1) wins.
        val overlap = state(emptyDesc(3))
        overlap.grid[0] = 2
        overlap.grid[3] = 3
        val u2 = Towers.newUi(overlap) as Towers.Ui
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(overlap, u2, 91, 91, Buttons.LEFT_BUTTON, 48),
        )
        assertEquals(0, u2.hx)
        assertEquals(1, u2.hy)
    }

    @Test
    fun `the cursor clamps and its result is the C one`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui

        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, 0, 0, Buttons.CURSOR_UP, 48),
        )
        assertTrue(u.hshow)
        assertTrue(u.hcursor)
        assertEquals(
            MoveResult.NoEffect,
            Towers.interpretMove(s, u, 0, 0, Buttons.CURSOR_UP, 48),
        )
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, 0, 0, Buttons.CURSOR_RIGHT, 48),
        )
        assertEquals(1, u.hx)
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, 0, 0, Buttons.CURSOR_SELECT, 48),
        )
        assertTrue(u.hpencil)
        assertTrue(u.hcursor)
    }

    @Test
    fun `digit entry, pencil entry, clear and mark follow the C strings`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        val (x0, y0) = click(0, 0)
        Towers.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48)

        assertEquals(
            MoveResult.Move("R0,0,3"),
            Towers.interpretMove(s, u, 0, 0, '3'.code, 48),
        )
        // A digit above the order is unused.
        assertEquals(
            MoveResult.Unused,
            Towers.interpretMove(s, u, 0, 0, '4'.code, 48),
        )

        val filled = Towers.executeMove(s, "R0,0,3") as Towers.State
        val uf = Towers.newUi(filled) as Towers.Ui
        Towers.interpretMove(filled, uf, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Move("R0,0,0"),
            Towers.interpretMove(filled, uf, 0, 0, Buttons.CURSOR_SELECT2, 48),
        )
        assertEquals(
            MoveResult.Move("R0,0,0"),
            Towers.interpretMove(filled, uf, 0, 0, '\b'.code, 48),
        )

        // A pencil highlight makes a positive digit a `P` move.
        u.hpencil = true
        assertEquals(
            MoveResult.Move("P0,0,3"),
            Towers.interpretMove(s, u, 0, 0, '3'.code, 48),
        )
        assertEquals(
            MoveResult.Unused,
            Towers.interpretMove(s, u, 0, 0, Buttons.CURSOR_SELECT2, 48),
        )

        // Mark works without a highlight, and both cases of the letter.
        val hidden = Towers.newUi(s) as Towers.Ui
        assertEquals(MoveResult.Move("M"), Towers.interpretMove(s, hidden, 0, 0, 'M'.code, 48))
        assertEquals(MoveResult.Move("M"), Towers.interpretMove(s, hidden, 0, 0, 'm'.code, 48))
    }

    @Test
    fun `a rejected pencil mark and a same-digit entry are unused`() {
        val s = state(emptyDesc(3))
        val filled = Towers.executeMove(s, "R0,0,3") as Towers.State

        val u = Towers.newUi(filled) as Towers.Ui
        Towers.interpretMove(filled, u, 0, 0, Buttons.CURSOR_UP, 48)
        Towers.interpretMove(filled, u, 0, 0, Buttons.CURSOR_SELECT, 48)
        assertTrue(u.hpencil)
        assertEquals(
            MoveResult.Unused,
            Towers.interpretMove(filled, u, 0, 0, '3'.code, 48),
        )

        // A same digit with an empty pencil bitmap is unused while the
        // preference keeps the highlight.
        val u2 = Towers.newUi(filled) as Towers.Ui
        val (x0, y0) = click(0, 0)
        Towers.interpretMove(filled, u2, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Unused,
            Towers.interpretMove(filled, u2, 0, 0, '3'.code, 48),
        )
        assertTrue(u2.hshow)

        // The same number with a pencil bitmap is a move, not a no-op.
        val pencilled = Towers.executeMove(s, "P0,0,3") as Towers.State
        val u3 = Towers.newUi(pencilled) as Towers.Ui
        Towers.interpretMove(pencilled, u3, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Move("R0,0,0"),
            Towers.interpretMove(pencilled, u3, 0, 0, '0'.code, 48),
        )
    }

    @Test
    fun `the pencil-keep-highlight preference hides a mouse highlight`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        u.pencilKeepHighlight = false
        val (x0, y0) = click(0, 0)
        Towers.interpretMove(s, u, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.Move("R0,0,3"),
            Towers.interpretMove(s, u, 0, 0, '3'.code, 48),
        )
        assertFalse(u.hshow)

        val u2 = Towers.newUi(s) as Towers.Ui
        u2.pencilKeepHighlight = false
        Towers.interpretMove(s, u2, x0, y0, Buttons.LEFT_BUTTON, 48)
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u2, 0, 0, '0'.code, 48),
        )
        assertFalse(u2.hshow)

        val u3 = Towers.newUi(s) as Towers.Ui
        Towers.interpretMove(s, u3, x0, y0, Buttons.LEFT_BUTTON, 48)
        Towers.interpretMove(s, u3, 0, 0, '0'.code, 48)
        assertTrue(u3.hshow)
    }

    @Test
    fun `drag, release and middle press are unused`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
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
                Towers.interpretMove(s, u, x0, y0, button, 48),
                "button $button",
            )
        }
        assertFalse(u.hshow)
        // A digit with no highlight is unused.
        assertEquals(
            MoveResult.Unused,
            Towers.interpretMove(s, u, 0, 0, '1'.code, 48),
        )
    }

    @Test
    fun `clue clicks and modified arrows produce D moves`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        // All clues are absent, so a click on the band clears instead.
        assertEquals(
            MoveResult.UiUpdate,
            Towers.interpretMove(s, u, 0, 0, Buttons.LEFT_BUTTON, 48),
        )
        // A modified arrow that misses a clue is unused.
        assertEquals(
            MoveResult.Unused,
            Towers.interpretMove(
                s,
                u,
                0,
                0,
                Buttons.MOD_CTRL or Buttons.CURSOR_LEFT,
                48,
            ),
        )

        val withClues = state("1/1/1/1/1/1/1/1/1/1/1/1")
        val u2 = Towers.newUi(withClues) as Towers.Ui
        // The top-left clue of column 0 is above the grid.
        assertEquals(
            MoveResult.Move("D0,-1"),
            Towers.interpretMove(withClues, u2, 60, 6, Buttons.LEFT_BUTTON, 48),
        )
        // A modified arrow on the cursor cell reaches the left band.
        u2.hx = 0
        u2.hy = 0
        assertEquals(
            MoveResult.Move("D-1,0"),
            Towers.interpretMove(
                withClues,
                u2,
                0,
                0,
                Buttons.MOD_SHFT or Buttons.CURSOR_LEFT,
                48,
            ),
        )
    }

    @Test
    fun `executeMove applies R, P, M, D and S`() {
        val s = state(emptyDesc(3))

        val r = Towers.executeMove(s, "R0,0,3") as Towers.State
        assertEquals(3, r.grid[0])
        assertEquals(0, r.pencil[0])
        assertFalse(r.completed)

        val p = Towers.executeMove(s, "P0,0,3") as Towers.State
        assertEquals(0, p.grid[0])
        assertEquals(1 shl 3, p.pencil[0])
        val toggle = Towers.executeMove(p, "P0,0,3") as Towers.State
        assertEquals(0, toggle.pencil[0])

        // P on a filled cell is accepted by C.
        val under = Towers.executeMove(r, "P0,0,3") as Towers.State
        assertEquals(3, under.grid[0])
        assertEquals(1 shl 3, under.pencil[0])

        // P0,0,0 takes the R branch and clears the cell.
        val cleared = Towers.executeMove(r, "P0,0,0") as Towers.State
        assertEquals(0, cleared.grid[0])
        assertEquals(0, cleared.pencil[0])

        val marked = Towers.executeMove(s, "M") as Towers.State
        for (i in 0 until 9) assertEquals((1 shl 4) - (1 shl 1), marked.pencil[i])
        assertFalse(marked.completed)

        val solved = Towers.executeMove(s, "S123231312") as Towers.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
        assertTrue(solved.pencil.all { it == 0 })

        val clues = state("1/1/1/1/1/1/1/1/1/1/1/1")
        val d = Towers.executeMove(clues, "D0,-1") as Towers.State
        assertTrue(d.cluesDone[0])
        val undone = Towers.executeMove(d, "D0,-1") as Towers.State
        assertFalse(undone.cluesDone[0])
    }

    @Test
    fun `executeMove rejects every invalid form`() {
        val s = state(emptyDesc(3))
        val bad = listOf(
            "",
            "X0,0,1",
            "S",
            "S123",
            "S12345678",
            "S1234567890",
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
            // A D move needs a drawn clue on a band, not a grid cell.
            "D0,0",
            "D-1,0",
            "D3,0",
        )
        for (move in bad) {
            assertNull(Towers.executeMove(s, move), "move '$move'")
        }
    }

    @Test
    fun `executeMove accepts the sscanf quirks`() {
        val s = state(emptyDesc(3))
        for (move in listOf("R 1,2,3", "R+1,2,3", "R1,2,3junk", "R1,2,3,4")) {
            val r = Towers.executeMove(s, move)
            assertNotNull(r, "move '$move'")
            assertEquals(3, (r as Towers.State).grid[2 * 3 + 1], "move '$move'")
        }
        val spaced = Towers.executeMove(s, "R1, 2, 3") as Towers.State
        assertEquals(3, spaced.grid[7])
    }

    @Test
    fun `completion is sticky and status follows it`() {
        var s: Game.State = state(emptyDesc(3))
        for (i in 0 until 9) {
            s = Towers.executeMove(s, "R${i % 3},${i / 3},${"123231312"[i]}")!!
        }
        assertTrue((s as Towers.State).completed)
        assertEquals(1, Towers.status(s))

        val broken = Towers.executeMove(s, "R0,0,1") as Towers.State
        assertTrue(broken.completed)
        assertEquals(1, Towers.status(broken))

        // M and D never complete anything.
        val fresh = state(emptyDesc(3))
        assertFalse((Towers.executeMove(fresh, "M") as Towers.State).completed)
        assertEquals(0, Towers.status(fresh))
    }

    @Test
    fun `checkErrors marks duplicates, the clue and the incomplete board`() {
        val empty = state(emptyDesc(3))
        val errors = BooleanArray(25)
        assertTrue(Towers.checkErrors(empty, errors))
        // An all-zero board marks no cell and no clue.
        assertTrue(errors.all { !it })

        val solved = Towers.executeMove(empty, "S123231312") as Towers.State
        errors.fill(true)
        assertFalse(Towers.checkErrors(solved, errors))
        assertTrue(errors.all { !it })

        // Two equal digits in a row are marked.
        val duplicate = empty.copy() as Towers.State
        duplicate.grid[0] = 3
        duplicate.grid[1] = 3
        errors.fill(false)
        assertTrue(Towers.checkErrors(duplicate, errors))
        assertTrue(errors[1 * 5 + 1])
        assertTrue(errors[1 * 5 + 2])

        // A duplicate in a column is marked too.
        val column = empty.copy() as Towers.State
        column.grid[0] = 2
        column.grid[3] = 2
        errors.fill(false)
        assertTrue(Towers.checkErrors(column, errors))
        assertTrue(errors[1 * 5 + 1])
        assertTrue(errors[2 * 5 + 1])

        // A satisfied clue is clean; an over-satisfied one is marked.
        val clues = state("1/1/1/1/1/1/1/1/1/1/1/1")
        val top = clues.copy() as Towers.State
        top.grid[0] = 3
        errors.fill(false)
        assertTrue(Towers.checkErrors(top, errors))
        // The top clue of column 0 is 1, and the visible prefix is 1
        // while the maximum is already present: the "count short" rule
        // does not fire, so the clue is clean.
        assertFalse(errors[0 * 5 + 1])
        // A 2 in the first cell is also one tower, but the maximum is
        // absent and the count matches, so the clue is marked.
        val short = clues.copy() as Towers.State
        short.grid[0] = 2
        errors.fill(false)
        assertTrue(Towers.checkErrors(short, errors))
        assertTrue(errors[0 * 5 + 1])
    }

    @Test
    fun `changedState cancels a pencil highlight and reports completion`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        val (x0, y0) = click(0, 0)
        Towers.interpretMove(s, u, x0, y0, Buttons.RIGHT_BUTTON, 48)
        assertTrue(u.hshow && u.hpencil)

        val filled = Towers.executeMove(s, "R0,0,1") as Towers.State
        assertFalse(Towers.changedState(u, s, filled))
        assertFalse(u.hshow)
        assertFalse(u.hpencil)

        val solved = solveByMoves(s, "S123231312")
        assertTrue(solved.completed)
        assertFalse(solved.cheated)
        assertTrue(Towers.changedState(Towers.newUi(s), s, solved))
        assertFalse(Towers.changedState(Towers.newUi(solved), solved, solved))
        val cheated = Towers.executeMove(s, "S123231312") as Towers.State
        assertFalse(Towers.changedState(Towers.newUi(s), s, cheated))
        assertFalse(Towers.changedState(Towers.newUi(s), null, solved))
    }

    @Test
    fun `undo and redo across a real move, a solve and a restart`() {
        val md = Midend.newGame(Towers, "3de", "1")
        assertFalse(md.canUndo)

        assertEquals(KeyOutcome.SomeEffect, md.processKey(0, 0, Buttons.CURSOR_UP, 48))
        assertFalse(md.canUndo)
        assertEquals(KeyOutcome.SomeEffect, md.processKey(0, 0, '1'.code, 48))
        assertTrue(md.canUndo)
        assertEquals(2, md.stateCount)
        assertEquals("R0,0,1", md.serialise().value("MOVE"))

        assertTrue(md.undo())
        assertEquals(2, md.stateCount)
        assertTrue(md.canRedo)
        assertTrue(md.redo())

        assertTrue(md.solve())
        assertEquals(1, md.status())
        assertTrue(md.undo())
        assertEquals(0, md.status())

        assertTrue(md.redo())
        assertTrue(md.restart())
        assertEquals(0, md.status())
        assertTrue(md.undo())
        assertEquals(1, md.status())
    }

    @Test
    fun `a real completion raises one event and undo and redo cross it`() {
        val md = Midend.newGame(Towers, "3de", "1")
        val aux = requireNotNull(md.auxInfo)
        for (i in 0 until 9) {
            val x = i % 3
            val y = i / 3
            val (px, py) = center(x, y)
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

    // ------------------------------------------------------------------
    // Solve and text.
    // ------------------------------------------------------------------

    @Test
    fun `the stored answer short-circuits the solver`() {
        val s = state(emptyDesc(3))
        assertEquals("S123456789", Towers.solve(s, "S123456789"))
    }

    @Test
    fun `solve returns the measured preset answers`() {
        val easy = Towers.newState(params("5de"), desc5)
        assertEquals(aux5, Towers.solve(easy, null))
        val hard = Towers.newState(params("5dh"), desc5h)
        assertEquals("S1523434125235414135252413", Towers.solve(hard, null))
    }

    @Test
    fun `solver errors are distinguishable and solve returns null`() {
        val impossible = Towers.newState(
            params("3de"),
            "3/3/3/".trimEnd('/') + "/".repeat(9),
        )
        assertEquals(LatinSolver.DIFF_IMPOSSIBLE, Towers.solveResult(impossible, 3))
        assertNull(Towers.solve(impossible, null))

        val ambiguous = Towers.newState(params("3de"), emptyDesc(3))
        assertEquals(LatinSolver.DIFF_AMBIGUOUS, Towers.solveResult(ambiguous, 3))
        assertNull(Towers.solve(ambiguous, null))
    }

    @Test
    fun `the solve strings are exactly S plus the solution digits`() {
        for (paramsText in Towers.presets().map { it.params }) {
            val p = params(paramsText)
            val generated = Towers.generateWithStats(p, RandomState.fromSeed("12345"))
            val s = Towers.newState(p, generated.description.text)
            val solved = Towers.solve(s, null)
            assertNotNull(solved, paramsText)
            assertEquals(p.w * p.w + 1, solved!!.length)
            assertTrue(solved.startsWith("S"))
            assertEquals(generated.description.auxInfo, solved)
            val applied = Towers.executeMove(s, solved) as Towers.State
            assertEquals(1, Towers.status(applied))
        }
    }

    @Test
    fun `textFormat is byte-exact for an empty, a solved and a pencilled board`() {
        val empty = state(emptyDesc(3))
        val w = 3
        assertEquals(
            " ".repeat(2 + 2 * w) + "\n" +
                "\n" +
                (" ".repeat(2 * w + 5) + "\n").repeat(w) +
                "\n" +
                " ".repeat(2 + 2 * w) + "\n",
            Towers.textFormat(empty),
        )

        val solved = Towers.executeMove(
            Towers.newState(params("5de"), desc5),
            aux5,
        ) as Towers.State
        assertEquals(
            "   3 2 2 2 1\n\n" +
                "5  1 2 3 4 5  1\n" +
                "2  4 5 1 2 3  2\n" +
                "3  2 4 5 3 1  3\n" +
                "1  5 3 4 1 2  3\n" +
                "2  3 1 2 5 4  2\n" +
                "\n" +
                "   2 4 3 1 2\n",
            Towers.textFormat(solved),
        )
        assertEquals(2 * 25 + 10 * 5 + 8, Towers.textFormat(solved).length)
        assertTrue(Towers.textFormat(solved).endsWith("\n"))

        // Pencil marks are not part of the text form.
        val marked = Towers.executeMove(state(emptyDesc(3)), "M") as Towers.State
        assertEquals(Towers.textFormat(state(emptyDesc(3))), Towers.textFormat(marked))
    }

    // ------------------------------------------------------------------
    // Frontend state, keys and drawing inputs.
    // ------------------------------------------------------------------

    @Test
    fun `the key request is the C list with no arrow dependency`() {
        for (p in Towers.presets()) {
            val request = Towers.requestKeys(params(p.params))
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
            assertEquals(request, Towers.requestKeys(params(p.params), true))
        }
    }

    @Test
    fun `currentKeyLabel follows the highlight and the pencil flag`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        assertEquals("", Towers.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("", Towers.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        u.hshow = true
        assertEquals("Pencil", Towers.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
        assertEquals("", Towers.currentKeyLabel(u, s, Buttons.CURSOR_SELECT2))
        u.hpencil = true
        assertEquals("Ink", Towers.currentKeyLabel(u, s, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `ui starts hidden with both preferences on and a load keeps them`() {
        val md = Midend.newGame(Towers, "3de", "1")
        val u = md.ui as Towers.Ui
        assertFalse(u.hshow)
        assertFalse(u.hcursor)
        assertTrue(u.threeD)
        assertTrue(u.pencilKeepHighlight)

        val loaded = Midend.fromSave(Towers, md.serialise())
        val lu = loaded.ui as Towers.Ui
        assertFalse(lu.hshow)
        assertTrue(lu.threeD)
        assertTrue(lu.pencilKeepHighlight)
        // Neither preference is serialised and Towers has no UI record.
        assertNull(Towers.encodeUi(lu))
        assertNull(loaded.serialise().value("UI"))
        assertFalse(md.serialise().serialise().contains("three_d"))
    }

    @Test
    fun `cursorLocation is the cell rectangle including the tower space`() {
        val s = state(emptyDesc(3))
        val u = Towers.newUi(s) as Towers.Ui
        assertNull(Towers.cursorLocation(u, s, 48))
        u.hshow = true
        u.hx = 1
        u.hy = 2
        assertEquals(Game.CursorRect(102, 150, 48, 48), Towers.cursorLocation(u, s, 48))
        u.hx = 0
        u.hy = 0
        assertEquals(Game.CursorRect(54, 54, 48, 48), Towers.cursorLocation(u, s, 48))
    }

    @Test
    fun `computeSize, the palette and the drawstate follow the C arithmetic`() {
        assertEquals(Size(348, 348), Towers.computeSize(params("5de"), 48))
        assertEquals(Size(455, 455), Towers.computeSize(params("5de"), 63))
        assertEquals(Size(594, 594), Towers.computeSize(params("5de"), 82))
        assertEquals(Size(724, 724), Towers.computeSize(params("5de"), 100))
        assertEquals(Size(396, 396), Towers.computeSize(params("6de"), 48))
        assertEquals(Size(518, 518), Towers.computeSize(params("6de"), 63))
        assertEquals(Size(676, 676), Towers.computeSize(params("6de"), 82))
        assertEquals(Size(824, 824), Towers.computeSize(params("6de"), 100))

        val palette = Towers.colours()
        assertEquals(21, palette.size)
        assertEquals(0.8f, palette[0])
        assertEquals(0.0f, palette[6])
        assertEquals(0.48f, palette[7], 1e-6f)
        assertEquals(0.624f, palette[9], 1e-6f)
        assertEquals(1.0f, palette[12], 1e-6f)
        assertEquals(0.4f, palette[15], 1e-6f)
        assertEquals(0.8f, palette[17], 1e-6f)
        assertEquals(0.8f / 1.5f, palette[20], 1e-6f)

        val custom = Towers.colours(floatArrayOf(0.5f, 0.25f, 0.75f))
        assertEquals(0.15f, custom[7], 1e-6f)
        assertEquals(0.39f, custom[9], 1e-6f)
        assertEquals(0.25f, custom[15], 1e-6f)
        assertEquals(0.125f, custom[16], 1e-6f)
        assertEquals(0.75f, custom[17], 1e-6f)

        val ds = Towers.newDrawState(state(emptyDesc(3)), 48, NoOpSink) as Towers.DrawState
        assertEquals(48, ds.tileSize)
        assertEquals(25, ds.tiles.size)
        assertEquals(100, ds.drawn.size)
        assertTrue(ds.drawn.all { it == -1L })
        assertEquals(25, ds.errtmp.size)
    }

    @Test
    fun `the game table fields are the C ones`() {
        assertEquals("Towers", Towers.name)
        assertEquals("towers", Towers.backendName)
        assertEquals(48, Towers.preferredTileSize)
        assertFalse(Towers.wantsStatusBar)
        assertTrue(Towers.canSolve)
        assertTrue(Towers.canFormatAsText)
        assertEquals(0xC00, Towers.flags)
        assertFalse(Towers.isTimed)
        val s = state(emptyDesc(3))
        assertEquals("", Towers.statusText(s, Towers.newUi(s)))
        // No params-aware text gate: the text is always available.
        assertTrue(Towers.textFormat(s).isNotEmpty())
    }

    /** A sink with no output, for the drawstate tests. */
    private object NoOpSink : DrawSink {
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun thickLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            thickness: Float,
            colour: Int,
        ) {}

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {}

        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {}
        override fun status(text: String) {}
        override fun inertiaFollow(isSolved: Boolean) {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }
}
