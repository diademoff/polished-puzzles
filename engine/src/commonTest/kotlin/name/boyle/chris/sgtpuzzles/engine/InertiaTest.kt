package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: inertia.
 *
 * Kotlin-only pins for the rules that the oracle protocol cannot show:
 * the parameter quirks, the validation order, the state and text
 * format, the move grammar, the click octants, the death and
 * completion transitions, the `S` payload, the solver hints, and the
 * D-008 area guard.
 *
 * The generator attempt counts come from a C counter probe built from
 * the vendored `inertia.c` with one increment in `gengrid`. They pin
 * the retry accounting that the oracle protocol does not expose.
 */
class InertiaTest {

    private companion object {
        const val TILE = 32
        const val SEED = "12345"

        const val GOLDEN_10X8 =
            "bswmsgmSbmbmgmgbwgbbmgmwgwwswswmbmssbgmswbsbggbsswgwggbgsmswwswmwssbgbmmwgsbgmwm"

        val OPEN_5X5 = "bbbbb" + "gbbbb" + "bbSbb" + "bbbbb" + "bbbbb"
    }

    private fun params(text: String): Inertia.Params =
        Inertia.decodeParams(text) as Inertia.Params

    private fun stateOf(paramsText: String, desc: String): Inertia.State =
        Inertia.newState(params(paramsText), desc) as Inertia.State

    private fun descOf(paramsText: String, seed: String): String =
        Inertia.newDesc(params(paramsText), RandomState.fromSeed(seed)).text

    /** Rows joined into one `DESC` string. */
    private fun rows(vararg rows: String): String = rows.joinToString("")

    private fun ui(deaths: Int = 0): Inertia.Ui = Inertia.Ui().also { it.deaths = deaths }

    private fun keypad(digit: Char): Int = Buttons.MOD_NUM_KEYPAD or digit.code

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `defaults and presets match the C tables`() {
        assertEquals(Inertia.Params(10, 8), Inertia.defaultParams())
        val presets = Inertia.presets()
        assertEquals(3, presets.size)
        assertEquals(listOf("10x8", "15x12", "20x16"), presets.map { it.params })
        assertEquals(listOf("10x8", "15x12", "20x16"), presets.map { it.title })
        assertEquals(32, Inertia.preferredTileSize)
        assertTrue(Inertia.wantsStatusBar)
        assertTrue(Inertia.canSolve)
    }

    @Test
    fun `decode follows atoi and the digit loop`() {
        assertEquals(Inertia.Params(10, 8), Inertia.decodeParams("10x8"))
        assertEquals(Inertia.Params(10, 8), Inertia.decodeParams("10x8junk"))
        assertEquals(Inertia.Params(10, 8), Inertia.decodeParams("10x8x9"))
        // `atoi` skips a space or a sign, the digit loop does not, so
        // no `x` is seen and the height equals the width.
        assertEquals(Inertia.Params(10, 10), Inertia.decodeParams("10"))
        assertEquals(Inertia.Params(10, 10), Inertia.decodeParams(" 10x8"))
        assertEquals(Inertia.Params(10, 10), Inertia.decodeParams("+10x8"))
        assertEquals(Inertia.Params(10, 0), Inertia.decodeParams("10x"))
        assertEquals(Inertia.Params(-1, -1), Inertia.decodeParams("-1x8"))
        assertEquals(Inertia.Params(0, 8), Inertia.decodeParams("0x8"))
        // C `atoi` overflow is undefined; the port saturates.
        assertEquals(
            Inertia.Params(Int.MAX_VALUE, 8),
            Inertia.decodeParams("99999999999x8"),
        )
    }

    @Test
    fun `encode always writes WxH and ignores full`() {
        val p = Inertia.Params(15, 12)
        assertEquals("15x12", Inertia.encodeParams(p, true))
        assertEquals("15x12", Inertia.encodeParams(p, false))
    }

    @Test
    fun `validation follows the C order and messages`() {
        assertEquals(
            "Width and height must both be at least two",
            Inertia.validateParams(Inertia.Params(1, 8), true),
        )
        assertEquals(
            "Width and height must both be at least two",
            Inertia.validateParams(Inertia.Params(8, 1), true),
        )
        assertEquals(
            "Width and height must both be at least two",
            Inertia.validateParams(Inertia.Params(0, 8), true),
        )
        assertEquals(
            "Grid area must be at least six squares",
            Inertia.validateParams(Inertia.Params(2, 2), true),
        )
        assertEquals(null, Inertia.validateParams(Inertia.Params(2, 3), true))
        assertEquals(null, Inertia.validateParams(Inertia.Params(3, 2), true))
        assertEquals(
            "Width times height must not be unreasonably large",
            Inertia.validateParams(Inertia.Params(65536, 65536), true),
        )
        // The C boundary: `INT_MAX / 46340` is exactly 46340.
        assertEquals(
            null,
            Inertia.validateParams(Inertia.Params(46340, 46340), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Inertia.validateParams(Inertia.Params(46341, 46341), true),
        )
        // `full` changes nothing.
        assertEquals(
            "Grid area must be at least six squares",
            Inertia.validateParams(Inertia.Params(2, 2), false),
        )
    }

    @Test
    fun `generation refuses a huge area before any RNG draw`() {
        // D-008: C accepts 46340x46340 and then does not finish.
        assertEquals(
            null,
            Inertia.validateParams(Inertia.Params(46340, 46340), true),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            Inertia.newDesc(Inertia.Params(46340, 46340), RandomState.fromSeed("1"))
        }
        assertEquals("Width times height is too large for this build", error.message)

        // The exact 2^20 boundary is accepted by the guard.
        assertEquals(
            null,
            Inertia.validateParams(Inertia.Params(1024, 1024), true),
        )
        val over = assertFailsWith<IllegalArgumentException> {
            Inertia.newDesc(Inertia.Params(1025, 1024), RandomState.fromSeed("1"))
        }
        assertEquals("Width times height is too large for this build", over.message)
    }

    @Test
    fun `the area guard leaves the RNG state untouched`() {
        // The guard must run before the first draw. A draw would change
        // the encoded state, so byte equality proves the order.
        val random = RandomState.fromSeed("guard")
        val before = random.encodeState()
        val error = assertFailsWith<IllegalArgumentException> {
            Inertia.newDesc(Inertia.Params(46340, 46340), random)
        }
        assertEquals("Width times height is too large for this build", error.message)
        assertEquals(before, random.encodeState())

        // The same applies just above the boundary.
        val near = RandomState.fromSeed("guard")
        val nearBefore = near.encodeState()
        assertFailsWith<IllegalArgumentException> {
            Inertia.newDesc(Inertia.Params(1025, 1024), near)
        }
        assertEquals(nearBefore, near.encodeState())
    }

    @Test
    fun `the generation boundary is exactly 2^20 cells`() {
        assertEquals(1 shl 20, Inertia.MAX_AREA)

        // The guard itself is pinned at the boundary: a full 1024x1024
        // generation would allocate and shuffle about 51 MB per attempt,
        // so the test calls the guard directly instead of newDesc.
        Inertia.requireGeneratableArea(1024, 1024)
        assertEquals(
            "Width times height is too large for this build",
            assertFailsWith<IllegalArgumentException> {
                Inertia.requireGeneratableArea(1025, 1024)
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            Inertia.requireGeneratableArea(1024, 1025)
        }
    }

    @Test
    fun `generated fixtures and attempt counts match the C probe`() {
        assertEquals(
            "10x8:$GOLDEN_10X8",
            Midend.newGame(Inertia, "10x8", SEED).id(),
        )
        assertEquals("2x3:wbgmSs", Midend.newGame(Inertia, "2x3", SEED).id())
        assertEquals("3x2:wbgmSs", Midend.newGame(Inertia, "3x2", SEED).id())
        assertEquals("2x4:Smwbsgbb", Midend.newGame(Inertia, "2x4", SEED).id())
        assertEquals("2x3:mgbsSw", Midend.newGame(Inertia, "2x3", "1").id())
        assertEquals("4x4:bggwssSgwmwsmmbb", Midend.newGame(Inertia, "4x4", "1").id())
        assertEquals("4x4:wswbggSmswbmsgbm", Midend.newGame(Inertia, "4x4", "7").id())

        fun attempts(paramsText: String, seed: String): Int =
            Inertia.newDescWithAttempts(
                params(paramsText),
                RandomState.fromSeed(seed),
            ).attempts

        // Real C counters. The 4x4 sweep of seeds 0..300 peaks at 3,
        // so the threshold bump at 50 distance failures is not reached
        // by any fixture. The accounting itself is pinned here.
        assertEquals(2, attempts("10x8", SEED))
        assertEquals(15, attempts("15x12", "another seed"))
        assertEquals(12, attempts("20x16", "0"))
        assertEquals(2, attempts("2x3", "1"))
        assertEquals(6, attempts("5x5", "119"))
        assertEquals(3, attempts("4x4", "58"))
        assertEquals(1, attempts("3x3", "12345"))
    }

    @Test
    fun `validate_desc follows the C order`() {
        assertEquals(
            "Not enough data to fill grid",
            Inertia.validateDesc(params("2x3"), "wbgmS"),
        )
        assertEquals(
            "Unrecognised character in game description",
            Inertia.validateDesc(params("2x3"), "wbgmSx"),
        )
        assertEquals(
            "Too much data to fit in grid",
            Inertia.validateDesc(params("2x3"), "wbgmSsx"),
        )
        assertEquals(
            "No starting square specified",
            Inertia.validateDesc(params("2x3"), "wbgmss"),
        )
        assertEquals(
            "More than one starting square specified",
            Inertia.validateDesc(params("2x3"), "wbgSSs"),
        )
        assertEquals(
            "No gems specified",
            Inertia.validateDesc(params("2x3"), "wbSmss"),
        )
        assertEquals(null, Inertia.validateDesc(params("2x3"), "wbgmSs"))
    }

    // ------------------------------------------------------------------
    // State and text.
    // ------------------------------------------------------------------

    @Test
    fun `newState reads the golden description`() {
        val s = stateOf("10x8", GOLDEN_10X8)
        assertEquals(7, s.px)
        assertEquals(0, s.py)
        assertEquals(16, s.gems)
        assertFalse(s.dead)
        assertFalse(s.cheated)
        assertNull(s.soln)
        assertEquals(0, s.solnPos)
        assertEquals(0, s.distanceMoved)
        assertEquals('s', s.grid[7])
        assertEquals(80, s.grid.size)
        assertEquals(Inertia.WALL, s.at(-1, 0))
        assertEquals(Inertia.WALL, s.at(10, 0))
    }

    @Test
    fun `newState requires the description length`() {
        assertFailsWith<IllegalArgumentException> {
            Inertia.newState(params("2x3"), "wbgmS")
        }
    }

    @Test
    fun `textFormat matches the C board`() {
        val start = stateOf("2x3", "wbgmSs")
        assertEquals(
            "+---+---+\n" +
                "|XXX|   |\n" +
                "+---+---+\n" +
                "| o | M |\n" +
                "+---+---+\n" +
                "|(@)|( )|\n" +
                "+---+---+\n",
            Inertia.textFormat(start),
        )

        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        assertEquals(
            "+---+---+---+\n" +
                "|( )|   |:-(|\n" +
                "+---+---+---+\n" +
                "|XXX|   |( )|\n" +
                "+---+---+---+\n",
            Inertia.textFormat(dead),
        )

        val completed = Inertia.executeMove(stateOf("3x2", "Sgswbb"), "2") as Inertia.State
        assertEquals(
            "+---+---+---+\n" +
                "|( )|   |(@)|\n" +
                "+---+---+---+\n" +
                "|XXX|   |   |\n" +
                "+---+---+---+\n",
            Inertia.textFormat(completed),
        )

        // Five columns and five rows: the open-board fixture.
        val open = openBoard()
        assertEquals(
            "+---+---+---+---+---+\n" +
                "|   |   |   |   |   |\n" +
                "+---+---+---+---+---+\n" +
                "| o |   |   |   |   |\n" +
                "+---+---+---+---+---+\n" +
                "|   |   |(@)|   |   |\n" +
                "+---+---+---+---+---+\n" +
                "|   |   |   |   |   |\n" +
                "+---+---+---+---+---+\n" +
                "|   |   |   |   |   |\n" +
                "+---+---+---+---+---+\n",
            Inertia.textFormat(open),
        )
    }

    @Test
    fun `status and statusText cover every branch`() {
        val fresh = stateOf("10x8", GOLDEN_10X8)
        assertEquals(0, Inertia.status(fresh))
        assertEquals("Gems: 16", Inertia.statusText(fresh, Inertia.newUi(fresh)))
        assertEquals("Gems: 16   Deaths: -3", Inertia.statusText(fresh, ui(-3)))

        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        assertEquals(0, Inertia.status(dead))
        assertEquals("DEAD!", Inertia.statusText(dead, Inertia.newUi(dead)))
        assertEquals("DEAD!   Deaths: 1", Inertia.statusText(dead, ui(1)))
        assertEquals("DEAD!   Deaths: 2", Inertia.statusText(dead, ui(2)))

        val completed = Inertia.executeMove(stateOf("3x2", "Sgswbb"), "2") as Inertia.State
        assertEquals(1, Inertia.status(completed))
        assertEquals("COMPLETED!", Inertia.statusText(completed, Inertia.newUi(completed)))

        val solved = Inertia.executeMove(stateOf("2x3", "mgbsSw"), "S1045") as Inertia.State
        assertEquals("Auto-solver used. Gems: 1", Inertia.statusText(solved, Inertia.newUi(solved)))
        assertEquals(0, Inertia.status(solved))

        val autoSolved = Inertia.executeMove(solved, "1") as Inertia.State
        val finished = Inertia.executeMove(autoSolved, "0") as Inertia.State
        assertNull(finished.soln)
        assertTrue(finished.cheated)
        assertEquals("Auto-solved.", Inertia.statusText(finished, Inertia.newUi(finished)))
        assertEquals(1, Inertia.status(finished))
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    private fun openBoard(gem: String = "gbbbb"): Inertia.State = stateOf(
        "5x5",
        rows(
            "bbbbb",
            gem,
            "bbSbb",
            "bbbbb",
            "bbbbb",
        ),
    )

    @Test
    fun `every direction digit is one move on the open board`() {
        val start = openBoard()
        val expected = mapOf(
            0 to (2 to 0),
            1 to (4 to 0),
            2 to (4 to 2),
            3 to (4 to 4),
            4 to (2 to 4),
            5 to (0 to 4),
            6 to (0 to 2),
            7 to (0 to 0),
        )
        for ((dir, target) in expected) {
            val moved = Inertia.executeMove(start, dir.toString()) as Inertia.State
            assertEquals(target.first, moved.px, "direction $dir column")
            assertEquals(target.second, moved.py, "direction $dir row")
            assertEquals(
                maxOf(
                    kotlin.math.abs(target.first - 2),
                    kotlin.math.abs(target.second - 2),
                ),
                moved.distanceMoved,
                "direction $dir distance",
            )
            assertFalse(moved.dead)
            assertEquals(1, moved.gems)
        }
    }

    @Test
    fun `move grammar follows atoi and the range check`() {
        val start = openBoard()
        for (text in listOf("0", "00", " 3", "+3", "3junk", "-0")) {
            assertNotNull(Inertia.executeMove(start, text), "move '$text'")
        }
        assertEquals(
            (2 to 0),
            Inertia.executeMove(start, "0")!!.let { (it as Inertia.State).px to it.py },
        )
        assertNull(Inertia.executeMove(start, "8"))
        assertNull(Inertia.executeMove(start, "-1"))
        assertNull(Inertia.executeMove(start, "99999999999"))

        // The empty move is direction 0, as `atoi("")` is zero.
        val empty = Inertia.executeMove(start, "") as Inertia.State
        assertEquals(2, empty.px)
        assertEquals(0, empty.py)
        assertEquals(1, empty.gems)
    }

    @Test
    fun `the movement loop collects, stops, and dies`() {
        // A slide over blank cells ends before a wall.
        val slide = Inertia.executeMove(openBoard(), "0") as Inertia.State
        assertEquals(2, slide.distanceMoved)
        assertEquals(0, slide.py)
        assertEquals(1, slide.gems)

        // A stop ends on the stop cell; the gem beyond is untouched.
        val stopBoard = stateOf(
            "5x5",
            rows("bbgbb", "bbsbb", "bbSbb", "bbbbb", "bbbbb"),
        )
        val stopped = Inertia.executeMove(stopBoard, "0") as Inertia.State
        assertEquals(1, stopped.py)
        assertEquals(2, stopped.px)
        assertEquals(1, stopped.gems)

        // A wall directly ahead rejects the move.
        val wallAhead = stateOf(
            "5x5",
            rows("bbbbb", "bbwbb", "bbSbb", "bbbbb", "bbgbb"),
        )
        assertNull(Inertia.executeMove(wallAhead, "0"))

        // A gem on the way is collected.
        val oneGem = Inertia.executeMove(openBoard("bbgbb"), "0") as Inertia.State
        assertEquals(0, oneGem.gems)
        assertEquals(Inertia.BLANK, oneGem.grid[1 * 5 + 2])
        assertEquals(1, Inertia.status(oneGem))

        // Two gems on one move.
        val twoGems = stateOf(
            "5x5",
            rows("bbgbb", "bbgbb", "bbSbb", "bbbbb", "bbbbb"),
        )
        val both = Inertia.executeMove(twoGems, "0") as Inertia.State
        assertEquals(0, both.gems)
        assertEquals(2, both.distanceMoved)
        assertEquals(1, Inertia.status(both))

        // A mine before a gem kills and leaves the gem.
        val mineFirst = stateOf(
            "5x5",
            rows("bbgbb", "bbmbb", "bbSbb", "bbbbb", "bbbbb"),
        )
        val died = Inertia.executeMove(mineFirst, "0") as Inertia.State
        assertTrue(died.dead)
        assertEquals(1, died.gems)
        assertEquals(0, Inertia.status(died))
        assertEquals("DEAD!", Inertia.statusText(died, Inertia.newUi(died)))

        // A gem before a mine is collected, then the player dies with
        // zero gems; a dead completed state is not a win.
        val gemFirst = stateOf(
            "5x5",
            rows("bbmbb", "bbgbb", "bbSbb", "bbbbb", "bbbbb"),
        )
        val deadWithAllGems = Inertia.executeMove(gemFirst, "0") as Inertia.State
        assertTrue(deadWithAllGems.dead)
        assertEquals(0, deadWithAllGems.gems)
        assertEquals(0, Inertia.status(deadWithAllGems))
    }

    @Test
    fun `executeMove rejects a move from a dead state`() {
        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        assertTrue(dead.dead)
        assertNull(Inertia.executeMove(dead, "4"))
    }

    @Test
    fun `dupGame clears dead but copy keeps it`() {
        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        assertTrue(dead.dead)
        assertFalse(Inertia.dupGame(dead).dead)
        assertTrue(dead.copy().let { (it as Inertia.State).dead })
        assertEquals(dead, dead.copy())
    }

    @Test
    fun `interpretMove maps keys exactly`() {
        val start = openBoard()
        val u = Inertia.newUi(start)
        assertEquals(MoveResult.Move("0"), Inertia.interpretMove(start, u, 0, 0, Buttons.CURSOR_UP, TILE))
        assertEquals(MoveResult.Move("4"), Inertia.interpretMove(start, u, 0, 0, Buttons.CURSOR_DOWN, TILE))
        assertEquals(MoveResult.Move("6"), Inertia.interpretMove(start, u, 0, 0, Buttons.CURSOR_LEFT, TILE))
        assertEquals(MoveResult.Move("2"), Inertia.interpretMove(start, u, 0, 0, Buttons.CURSOR_RIGHT, TILE))
        assertEquals(MoveResult.Move("0"), Inertia.interpretMove(start, u, 0, 0, keypad('8'), TILE))
        assertEquals(MoveResult.Move("4"), Inertia.interpretMove(start, u, 0, 0, keypad('2'), TILE))
        assertEquals(MoveResult.Move("6"), Inertia.interpretMove(start, u, 0, 0, keypad('4'), TILE))
        assertEquals(MoveResult.Move("2"), Inertia.interpretMove(start, u, 0, 0, keypad('6'), TILE))
        assertEquals(MoveResult.Move("7"), Inertia.interpretMove(start, u, 0, 0, keypad('7'), TILE))
        assertEquals(MoveResult.Move("5"), Inertia.interpretMove(start, u, 0, 0, keypad('1'), TILE))
        assertEquals(MoveResult.Move("1"), Inertia.interpretMove(start, u, 0, 0, keypad('9'), TILE))
        assertEquals(MoveResult.Move("3"), Inertia.interpretMove(start, u, 0, 0, keypad('3'), TILE))

        // An exact compare: no modifier is stripped.
        assertEquals(MoveResult.Unused, Inertia.interpretMove(start, u, 0, 0, '8'.code, TILE))
        assertEquals(MoveResult.Unused, Inertia.interpretMove(start, u, 0, 0, keypad('5'), TILE))
        assertEquals(
            MoveResult.Unused,
            Inertia.interpretMove(start, u, 0, 0, Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, TILE),
        )
        assertEquals(
            MoveResult.Unused,
            Inertia.interpretMove(start, u, 0, 0, Buttons.CURSOR_SELECT, TILE),
        )
    }

    @Test
    fun `a click is the nearest compass octant with no bounds test`() {
        val start = openBoard()
        val u = Inertia.newUi(start)
        fun click(x: Int, y: Int): MoveResult =
            Inertia.interpretMove(start, u, x, y, Buttons.LEFT_BUTTON, TILE)

        // Cell centres around the player at (2,2); COORD(2) = 8 + 64.
        val cx = 8 + 2 * TILE + TILE / 2
        val cy = 8 + 2 * TILE + TILE / 2
        assertEquals(MoveResult.Move("0"), click(cx, cy - TILE))
        assertEquals(MoveResult.Move("1"), click(cx + TILE, cy - TILE))
        assertEquals(MoveResult.Move("2"), click(cx + TILE, cy))
        assertEquals(MoveResult.Move("3"), click(cx + TILE, cy + TILE))
        assertEquals(MoveResult.Move("4"), click(cx, cy + TILE))
        assertEquals(MoveResult.Move("5"), click(cx - TILE, cy + TILE))
        assertEquals(MoveResult.Move("6"), click(cx - TILE, cy))
        assertEquals(MoveResult.Move("7"), click(cx - TILE, cy - TILE))

        // The player's own tile is inert.
        assertEquals(MoveResult.Unused, click(cx, cy))

        // A click outside the board rectangle is a real move: there is
        // no bounds test. `(0,0)` is cell (-1,-1), the up-left octant.
        assertEquals(MoveResult.Move("7"), click(0, 0))
        // A negative x is a real move too, the C letterbox behavior.
        assertEquals(MoveResult.Move("6"), click(-1, cy))
        // A click in the letterbox band above the board is direction 0.
        assertEquals(MoveResult.Move("0"), click(cx, 0))

        // Exact tile boundaries use C truncation, not floorDiv.
        assertEquals(MoveResult.Move("6"), click(8 + 2 * TILE - 1, cy))
        assertEquals(MoveResult.Unused, click(8 + 2 * TILE, cy))
        assertEquals(MoveResult.Move("4"), click(cx, 8 + (2 + 1) * TILE))
        // The row above the first row is cell -1; a click there aims up.
        assertEquals(MoveResult.Move("0"), click(cx, 7))
    }

    @Test
    fun `the atan2 sectors match the C table`() {
        // A 9x9 open board so the deltas stay on the board.
        val s = stateOf(
            "9x9",
            "g" + "b".repeat(4 * 9 - 1) + "bbbb" + "S" + "bbbb" +
                "b".repeat(4 * 9),
        )
        val u = Inertia.newUi(s)
        fun at(dx: Int, dy: Int): Int {
            val cx = 8 + 4 * TILE + TILE / 2
            val cy = 8 + 4 * TILE + TILE / 2
            val result = Inertia.interpretMove(
                s, u, cx + dx * TILE, cy + dy * TILE, Buttons.LEFT_BUTTON, TILE,
            ) as MoveResult.Move
            return result.text.toInt()
        }
        assertEquals(1, at(1, -2))
        assertEquals(0, at(1, -3))
        assertEquals(1, at(2, -1))
        assertEquals(2, at(3, -1))
    }

    @Test
    fun `direction moves go through the midend`() {
        val midend = Midend.fromGameId(Inertia, "5x5:$OPEN_5X5")
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, keypad('8'), TILE))
        assertEquals(listOf("0"), midend.serialise().values("MOVE"))
        assertEquals(2, midend.stateCount)

        assertTrue(midend.undo())
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, keypad('9'), TILE))
        assertEquals(listOf("1"), midend.serialise().values("MOVE"))
    }

    // ------------------------------------------------------------------
    // Deaths and completion.
    // ------------------------------------------------------------------

    @Test
    fun `changedState counts deaths and reports completion`() {
        val u = Inertia.newUi(stateOf("3x2", "Sgmwbs")) as Inertia.Ui
        val live = stateOf("3x2", "Sgmwbs")
        val dead = Inertia.executeMove(live, "2") as Inertia.State

        // A live suicide move increments once and never completes.
        u.justMadeMove = true
        assertFalse(Inertia.changedState(u, live, dead))
        assertEquals(1, u.deaths)
        assertTrue(u.justDied)
        assertFalse(u.justMadeMove)
        // A redo into the same dead state does not count again.
        assertFalse(Inertia.changedState(u, live, dead))
        assertEquals(1, u.deaths)

        // A real completion needs an old state.
        val completed = Inertia.executeMove(stateOf("3x2", "Sgswbb"), "2") as Inertia.State
        assertTrue(Inertia.changedState(u, stateOf("3x2", "Sgswbb"), completed))
        assertFalse(Inertia.changedState(u, null, completed))
        assertEquals(1, u.deaths)
    }

    @Test
    fun `a death needs the midend move flag`() {
        val midend = Midend.fromGameId(Inertia, "3x2:Sgmwbs")
        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        // A redo path calls changedState with just_made_move false.
        assertFalse(Inertia.changedState(midend.ui, midend.state, dead))
        assertEquals(0, (midend.ui as Inertia.Ui).deaths)

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, keypad('6'), TILE))
        assertEquals(1, (midend.ui as Inertia.Ui).deaths)
        assertTrue(midend.state.let { (it as Inertia.State).dead })
        assertEquals("DEAD!   Deaths: 1", midend.statusText())
        assertEquals(0, midend.status())
    }

    @Test
    fun `undo and redo keep the death count and completion`() {
        val midend = Midend.fromGameId(Inertia, "3x2:Sgswbb")
        assertFalse(midend.takeCompletedEvent())
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, keypad('6'), TILE))
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())
        assertEquals(1, midend.status())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.redo())
        assertTrue(midend.takeCompletedEvent())
        assertEquals(2, midend.stateCount)

        // A restart keeps the UI, so deaths survive.
        val fatal = Midend.fromGameId(Inertia, "3x2:Sgmwbs")
        fatal.processKey(0, 0, keypad('6'), TILE)
        assertEquals(1, (fatal.ui as Inertia.Ui).deaths)
        assertTrue(fatal.restart())
        assertEquals(1, (fatal.ui as Inertia.Ui).deaths)
        assertEquals("Gems: 1   Deaths: 1", fatal.statusText())
    }

    @Test
    fun `a solve never raises the completion event`() {
        val midend = Midend.fromGameId(Inertia, "2x3:mgbsSw")
        assertTrue(midend.solve())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertEquals(
            listOf("S1045"),
            midend.serialise().values("SOLVE"),
        )
        assertTrue((midend.state as Inertia.State).cheated)
        assertEquals("Auto-solver used. Gems: 1", midend.statusText())

        // Follow the tour: the first select applies direction 1.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertEquals("1", midend.serialise().values("MOVE").single())
        assertEquals("Auto-solver used. Gems: 1", midend.statusText())
        assertEquals(0, midend.status())

        // The second select collects the last gem. `cheated` stays, so
        // the completion event is suppressed.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertEquals(1, midend.status())
        assertEquals("Auto-solved.", midend.statusText())
        assertFalse(midend.takeCompletedEvent())
    }

    // ------------------------------------------------------------------
    // The S payload and the revive quirk.
    // ------------------------------------------------------------------

    @Test
    fun `the S payload is not validated`() {
        val start = openBoard()
        assertNull(Inertia.executeMove(start, "S"))

        val eight = Inertia.executeMove(start, "S8") as Inertia.State
        assertTrue(eight.cheated)
        assertEquals(1, eight.soln!!.len)
        assertEquals(8, eight.soln.directionAt(0))
        assertEquals(start.px, eight.px)
        assertEquals(start.py, eight.py)
        assertTrue(eight.grid.contentEquals(start.grid))

        val colon = Inertia.executeMove(start, "S:") as Inertia.State
        assertEquals(10, colon.soln!!.directionAt(0))
        val x = Inertia.executeMove(start, "Sx") as Inertia.State
        assertEquals(72, x.soln!!.directionAt(0))
        // C stores an unsigned byte, so `space - '0'` is 240, not -16.
        val space = Inertia.executeMove(start, "S  ") as Inertia.State
        assertEquals(2, space.soln!!.len)
        assertEquals(240, space.soln.directionAt(0))
        assertEquals(240, space.soln.directionAt(1))
        val negative = Inertia.executeMove(start, "S-1") as Inertia.State
        assertEquals(253, negative.soln!!.directionAt(0))
        assertEquals(1, negative.soln.directionAt(1))
    }

    @Test
    fun `a payload byte outside 0 to 7 is NoEffect, not an abort`() {
        val start = openBoard()
        val state = Inertia.executeMove(start, "S8") as Inertia.State
        val ui = Inertia.newUi(state)

        // C returns the move text and then aborts in the midend. The
        // port returns a move that `executeMove` rejects, so the
        // midend reports NoEffect.
        assertEquals(
            MoveResult.Move("8"),
            Inertia.interpretMove(state, ui, 0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        assertNull(Inertia.executeMove(state, "8"))

        val midend = Midend.fromSave(
            Inertia,
            SgtpSave.parse(saveText("5x5", OPEN_5X5, listOf("SOLVE" to "S8"))),
        )
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        // The load itself has the SOLVE entry; the rejected select adds
        // no MOVE, and the player has not moved.
        assertEquals(2, midend.stateCount)
        assertTrue(midend.serialise().values("MOVE").isEmpty())
        assertEquals(state.px, (midend.state as Inertia.State).px)
    }

    @Test
    fun `a solve move revives a dead state`() {
        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        assertTrue(dead.dead)
        val revived = Inertia.executeMove(dead, "S1375") as Inertia.State
        assertFalse(revived.dead)
        assertTrue(revived.cheated)
        assertEquals(dead.px, revived.px)
        assertEquals(0, revived.solnPos)
        assertEquals(listOf(1, 3, 7, 5), revived.soln!!.directions.toList())
    }

    // ------------------------------------------------------------------
    // UI.
    // ------------------------------------------------------------------

    @Test
    fun `encodeUi and decodeUi follow the sscanf prefix`() {
        for ((text, deaths, encoded) in listOf(
            Triple("D0", 0, "D0"),
            Triple("D5", 5, "D5"),
            Triple("D 7", 7, "D7"),
            Triple("D+8", 8, "D8"),
            Triple("D-3", -3, "D-3"),
            Triple("X9", 0, "D0"),
            Triple("D", 0, "D0"),
            Triple("D12junk", 12, "D12"),
            Triple(" D4", 0, "D0"),
            Triple("D004", 4, "D4"),
        )) {
            val decoded = Inertia.decodeUi(stateOf("2x3", "wbgmSs"), text) as Inertia.Ui
            assertEquals(deaths, decoded.deaths, "decode '$text'")
            assertEquals(encoded, Inertia.encodeUi(decoded), "encode '$text'")
        }
        assertEquals("D0", Inertia.encodeUi(Inertia.newUi(stateOf("2x3", "wbgmSs"))))
    }

    @Test
    fun `currentKeyLabel is Advance only for a live select`() {
        val start = openBoard()
        assertEquals("", Inertia.currentKeyLabel(Inertia.newUi(start), start, Buttons.CURSOR_SELECT))
        val solved = Inertia.executeMove(start, "S0123") as Inertia.State
        assertEquals("Advance", Inertia.currentKeyLabel(Inertia.newUi(solved), solved, Buttons.CURSOR_SELECT))
        assertEquals("Advance", Inertia.currentKeyLabel(Inertia.newUi(solved), solved, Buttons.CURSOR_SELECT2))
        assertEquals(
            "",
            Inertia.currentKeyLabel(
                Inertia.newUi(solved),
                solved,
                Buttons.MOD_CTRL or Buttons.CURSOR_SELECT,
            ),
        )
        assertEquals("", Inertia.currentKeyLabel(Inertia.newUi(solved), solved, ' '.code))
    }

    // ------------------------------------------------------------------
    // Solver.
    // ------------------------------------------------------------------

    @Test
    fun `the solver reproduces the C solve strings`() {
        fun solve(paramsText: String, seed: String): String? {
            val midend = Midend.newGame(Inertia, paramsText, seed)
            return Inertia.solve(midend.state)
        }
        assertEquals("S04", solve("2x3", SEED))
        assertEquals("S15", solve("3x2", SEED))
        assertEquals("S34717", solve("2x4", SEED))
        assertEquals("S1045", solve("2x3", "1"))
        assertEquals("S73274", solve("4x4", "1"))
        assertEquals(
            "S3456665310306435562171353167133140700",
            solve("10x8", SEED),
        )
        assertEquals(
            "S6507603034434671010056414313431242454277622101164670535104635551111325517",
            solve("15x12", SEED),
        )
        assertEquals(
            "S643165066456220061046232301076035461073311321523013253445355516340315701407107034644454620167757015046546533725725171173323434034066043",
            solve("20x16", SEED),
        )
    }

    @Test
    fun `the solver fails on a solved or a dead state`() {
        val solved = Inertia.executeMove(stateOf("2x3", "wbgmSs"), "0") as Inertia.State
        assertEquals(0, solved.gems)
        assertNull(Inertia.solve(solved))

        val dead = Inertia.executeMove(stateOf("3x2", "Sgmbgs"), "2") as Inertia.State
        assertTrue(dead.dead)
        assertNull(Inertia.solve(dead))
    }

    @Test
    fun `the solver follows, advances, and recomputes`() {
        val start = openBoard()
        val solved = Inertia.executeMove(start, "S0123") as Inertia.State
        assertEquals(0, solved.solnPos)
        assertEquals(0, solved.soln!!.directionAt(0))

        val ui = Inertia.newUi(solved)
        assertEquals(
            MoveResult.Move("0"),
            Inertia.interpretMove(solved, ui, 0, 0, Buttons.CURSOR_SELECT, TILE),
        )
        val advanced = Inertia.executeMove(solved, "0") as Inertia.State
        assertEquals(1, advanced.solnPos)
        assertEquals(1, advanced.soln!!.directionAt(1))

        // A different direction recomputes a fresh tour at position 0.
        val recomputed = Inertia.executeMove(solved, "4") as Inertia.State
        assertNotNull(recomputed.soln)
        assertEquals(0, recomputed.solnPos)
        assertTrue(recomputed.cheated)
        assertEquals(1, recomputed.gems)
    }

    // ------------------------------------------------------------------
    // Geometry, palette, keys, drawing.
    // ------------------------------------------------------------------

    @Test
    fun `computeSize uses the T over 4 border`() {
        val p = params("10x8")
        assertEquals(Size(337, 273), Inertia.computeSize(p, 32))
        assertEquals(Size(295, 239), Inertia.computeSize(p, 28))
        assertEquals(Size(409, 331), Inertia.computeSize(p, 39))
        assertEquals(Size(403, 325), Inertia.computeSize(params("15x12"), 26))
        assertEquals(Size(411, 331), Inertia.computeSize(params("20x16"), 20))
    }

    @Test
    fun `the palette matches the frozen colour table`() {
        val colours = Inertia.colours()
        val expected = listOf(
            intArrayOf(800, 800, 800),
            intArrayOf(0, 0, 0),
            intArrayOf(967, 967, 967),
            intArrayOf(633, 633, 633),
            intArrayOf(0, 1000, 0),
            intArrayOf(1000, 300, 0),
            intArrayOf(0, 0, 0),
            intArrayOf(600, 1000, 1000),
            intArrayOf(842, 842, 842),
            intArrayOf(1000, 1000, 0),
        )
        for ((index, rgb) in expected.withIndex()) {
            for (channel in 0 until 3) {
                assertEquals(
                    rgb[channel],
                    (colours[index * 3 + channel] * 1000.0f).roundToInt(),
                    "colour $index channel $channel",
                )
            }
        }
        // The wall follows a non-default background.
        val dark = Inertia.colours(floatArrayOf(0.2f, 0.2f, 0.2f))
        assertTrue((dark[8 * 3] * 1000.0f).roundToInt() != 842)
    }

    @Test
    fun `requestKeys asks for the diagonal arrows`() {
        val request = Inertia.requestKeys(params("10x8"))
        assertEquals(ArrowMode.ARROWS_DIAGONALS, request.arrowMode)
        assertTrue(request.labels.isEmpty())
    }

    @Test
    fun `cursorLocation is the settled sprite rectangle`() {
        val s = stateOf("10x8", GOLDEN_10X8)
        val rect = Inertia.cursorLocation(Inertia.newUi(s), s, 32)!!
        assertEquals(8 + 7 * 32, rect.left)
        assertEquals(8, rect.top)
        assertEquals(32, rect.width)
        assertEquals(32, rect.height)
    }

    @Test
    fun `drawing emits the follow callback before the status`() {
        val s = openBoard()
        val u = Inertia.newUi(s)
        val sink = RecordingSink()
        val ds = Inertia.newDrawState(s, TILE, sink) as Inertia.DrawState

        Inertia.redraw(s, u, ds, sink, TILE)
        val first = sink.ops.toList()
        assertEquals(12, first.count { it == "line" })
        assertEquals(
            listOf("inertia_follow", "status", "blitter_save"),
            first.filter { it == "inertia_follow" || it == "status" || it == "blitter_save" },
        )
        assertTrue(first.lastIndexOf("status") < first.indexOf("blitter_save"))
        assertTrue(first.lastIndexOf("circle") > first.indexOf("blitter_save"))
        assertTrue(ds.started)

        // A second draw of the same state draws no cell and no line.
        sink.ops.clear()
        Inertia.redraw(s, u, ds, sink, TILE)
        assertFalse(sink.ops.contains("line"))
        assertFalse(sink.ops.contains("clip"))
        assertEquals(
            listOf("blitter_load", "update", "inertia_follow", "status", "blitter_save", "circle", "update"),
            sink.ops,
        )

        // A move that collects a gem redraws only the changed cells;
        // the grid lines stay.
        val gemBoard = openBoard("bbgbb")
        val gemState = openBoard("bbgbb")
        val gemDrawState = Inertia.newDrawState(gemState, TILE, sink) as Inertia.DrawState
        Inertia.redraw(gemState, u, gemDrawState, sink, TILE)
        val moved = Inertia.executeMove(gemBoard, "0") as Inertia.State
        sink.ops.clear()
        Inertia.redraw(moved, u, gemDrawState, sink, TILE)
        assertFalse(sink.ops.contains("line"))
        assertTrue(sink.ops.indexOf("blitter_load") >= 0)
        assertTrue(sink.ops.contains("clip"))
        assertTrue(sink.ops.indexOf("blitter_save") > sink.ops.indexOf("clip"))
    }

    @Test
    fun `a dead frame blanks the mine under the player and draws the star`() {
        val dead = Inertia.executeMove(stateOf("3x2", "Sgmwbs"), "2") as Inertia.State
        val sink = RecordingSink()
        val ds = Inertia.newDrawState(dead, TILE, sink) as Inertia.DrawState
        Inertia.redraw(dead, Inertia.newUi(dead), ds, sink, TILE)

        // The player cell caches BLANK, so an undo redraws the mine.
        assertEquals(Inertia.BLANK.code, ds.grid[2])
        assertTrue(sink.ops.count { it == "poly" } >= 1)
        assertEquals("DEAD!", (sink.statusText))
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private fun saveText(
        paramsText: String,
        desc: String,
        history: List<Pair<String, String>>,
        ui: String = "D0",
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to SgtpSave.MAGIC
        records += "VERSION" to SgtpSave.VERSION
        records += "GAME" to Inertia.name
        records += "PARAMS" to paramsText
        records += "CPARAMS" to paramsText
        records += "DESC" to desc
        records += "UI" to ui
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        for ((key, value) in history) records += key to value
        return records.joinToString("") { (key, value) -> "${key.padEnd(8)}:${value.length}:$value\n" }
    }

    /** A sink that records operation names and the last status text. */
    private class RecordingSink : DrawSink {
        val ops = ArrayList<String>()
        var statusText: String = ""

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
            ops += "rect"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
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
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly"
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
            ops += "update"
        }

        override fun status(text: String) {
            statusText = text
            ops += "status"
        }

        override fun inertiaFollow(isSolved: Boolean) {
            ops += "inertia_follow"
        }

        override fun blitterNew(width: Int, height: Int): Int {
            ops += "blitter_new"
            return 1
        }

        override fun blitterFree(id: Int) {
            ops += "blitter_free"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load"
        }
    }
}
