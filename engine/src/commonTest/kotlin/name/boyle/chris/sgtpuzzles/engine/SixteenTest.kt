package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges of the Sixteen port. */
class SixteenTest {

    private fun params(width: Int, height: Int, moveTarget: Int = 0) =
        Sixteen.Params(width, height, moveTarget)

    @Test
    fun `parameters decode as C does`() {
        // The digit cursor starts at the beginning, so a sign or space
        // stops it and the rest of the string is not special-cased.
        assertEquals(params(4, 4), Sixteen.decodeParams("4x4"))
        assertEquals(params(4, 4), Sixteen.decodeParams("4"))
        assertEquals(params(4, 4), Sixteen.decodeParams("04x04"))
        assertEquals(params(4, 4), Sixteen.decodeParams("+4x4"))
        assertEquals(params(4, 4), Sixteen.decodeParams(" 4x4"))
        assertEquals(params(4, 4), Sixteen.decodeParams("4x 4"))
        assertEquals(params(4, 4), Sixteen.decodeParams("4,4"))
        assertEquals(params(4, 4), Sixteen.decodeParams("4x4x4"))
        assertEquals(params(4, 4), Sixteen.decodeParams("4e"))
        assertEquals(params(4, 4), Sixteen.decodeParams("4x4 "))
        assertEquals(params(4, 4), Sixteen.decodeParams("4x4m"))
        assertEquals(params(4, 4, 10), Sixteen.decodeParams("4x4m0010"))
        assertEquals(params(4, 4, 2), Sixteen.decodeParams("4x4m+2"))
        assertEquals(params(4, 4, 10), Sixteen.decodeParams("4m10"))
        assertEquals(params(4, 4, 10), Sixteen.decodeParams("4m10x4"))
        assertEquals(params(4, 4, 10), Sixteen.decodeParams("4x4m10m3"))
        assertEquals(params(4, 4, 10), Sixteen.decodeParams("4x4m10x3"))
        assertEquals(params(4, 4, 0), Sixteen.decodeParams("4x4m0"))
        assertEquals(params(0, 4), Sixteen.decodeParams("x4"))
        assertEquals(params(0, 0, 10), Sixteen.decodeParams("m10"))
        assertEquals(params(0, 0), Sixteen.decodeParams(""))
        assertEquals(params(4, 0), Sixteen.decodeParams("4x"))
    }

    @Test
    fun `encoding keeps the target in the short form too`() {
        assertEquals("4x4", Sixteen.encodeParams(params(4, 4), true))
        assertEquals("4x4", Sixteen.encodeParams(params(4, 4), false))
        // `encode_params` ignores its `full` argument, so the game id and
        // both save parameters carry `m`.
        assertEquals("4x4m10", Sixteen.encodeParams(params(4, 4, 10), true))
        assertEquals("4x4m10", Sixteen.encodeParams(params(4, 4, 10), false))

        val midend = Midend.newGame(Sixteen, "4x4m10", "12345")
        assertTrue(midend.id().startsWith("4x4m10:"))
        assertEquals("4x4m10", midend.serialise().value("PARAMS"))
        assertEquals("4x4m10", midend.serialise().value("CPARAMS"))
    }

    @Test
    fun `validation messages are exact and ordered`() {
        assertEquals(null, Sixteen.validateParams(params(4, 4), true))
        assertEquals(null, Sixteen.validateParams(params(2, 2), true))
        assertEquals(
            "Width and height must both be at least two",
            Sixteen.validateParams(params(1, 4), true),
        )
        assertEquals(
            "Width and height must both be at least two",
            Sixteen.validateParams(params(0, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Sixteen.validateParams(params(100000, 100000), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Sixteen.validateParams(params(65536, 65536), true),
        )
        assertEquals(
            "Number of shuffling moves may not be negative",
            Sixteen.validateParams(params(4, 4, -1), true),
        )

        // `46341 * 46341` overflows `int`; `46340 * 46340` does not.
        assertEquals(
            "Width times height must not be unreasonably large",
            Sixteen.validateParams(params(46341, 46341), true),
        )
        assertEquals(null, Sixteen.validateParams(params(46340, 46340), true))
    }

    @Test
    fun `presets, identity, and keys`() {
        assertEquals(
            listOf("3x3", "4x3", "4x4", "5x4", "5x5"),
            Sixteen.presets().map { it.title },
        )
        assertEquals(
            listOf("3x3", "4x3", "4x4", "5x4", "5x5"),
            Sixteen.presets().map { it.params },
        )
        assertEquals("Sixteen", Sixteen.name)
        assertEquals("sixteen", Sixteen.backendName)
        assertEquals(48, Sixteen.preferredTileSize)
        assertTrue(Sixteen.wantsStatusBar)
        assertTrue(Sixteen.canSolve)

        // `game_request_keys` is NULL, so the midend's default arrow mode
        // applies and there are no extra labels.
        val keys = Sixteen.requestKeys(Sixteen.defaultParams())
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, keys.arrowMode)
        assertTrue(keys.labels.isEmpty())
    }

    @Test
    fun `descriptions are validated with the C messages`() {
        val p = Sixteen.decodeParams("4x4")
        val solved = (1..16).joinToString(",")
        assertEquals(null, Sixteen.validateDesc(p, solved))
        // Leading zeros are accepted by `atoi`, and the text is kept, so
        // the same board has two distinct ids.
        assertEquals(null, Sixteen.validateDesc(p, (1..16).joinToString(",") { "0$it" }))

        // 15 numbers leave no comma after the 15th, which the comma
        // test reports before the count is known.
        assertEquals(
            "Expected comma after number",
            Sixteen.validateDesc(p, (1..15).joinToString(",")),
        )
        assertEquals(
            "Not enough numbers in string",
            Sixteen.validateDesc(p, ""),
        )
        assertEquals(
            "Expected comma after number",
            Sixteen.validateDesc(p, (1..16).joinToString(";")),
        )
        assertEquals(
            "Not enough numbers in string",
            Sixteen.validateDesc(p, " " + solved),
        )
        assertEquals(
            "Not enough numbers in string",
            Sixteen.validateDesc(p, "+" + solved),
        )
        assertEquals(
            "Not enough numbers in string",
            Sixteen.validateDesc(p, "-" + solved),
        )
        assertEquals(
            "Excess junk at end of string",
            Sixteen.validateDesc(p, solved + ","),
        )
        assertEquals(
            "Excess junk at end of string",
            Sixteen.validateDesc(p, (1..17).joinToString(",")),
        )
        assertEquals(
            "Excess junk at end of string",
            Sixteen.validateDesc(p, solved + "x"),
        )
        assertEquals(
            "Number out of range",
            Sixteen.validateDesc(p, "0" + solved.substring(1)),
        )
        assertEquals(
            "Number out of range",
            Sixteen.validateDesc(p, solved.replace("16", "17")),
        )
        assertEquals(
            "Number used twice",
            Sixteen.validateDesc(p, solved.replace("15,16", "15,15")),
        )
        assertEquals(
            "Expected comma after number",
            Sixteen.validateDesc(p, solved.replace("1,2", "1 2")),
        )
        // There is no reachability check: an odd permutation on an odd
        // board is accepted.
        assertEquals(null, Sixteen.validateDesc(Sixteen.decodeParams("3x3"), "2,1,3,4,5,6,7,8,9"))
    }

    @Test
    fun `descriptions use the rank generator for a plain game`() {
        // Verified against the oracle for seed 12345.
        assertEquals(
            "3x3:6,8,7,3,2,5,4,1,9",
            Midend.newGame(Sixteen, "3x3", "12345").id(),
        )
        assertEquals(
            "4x3:8,12,11,2,7,6,9,10,5,1,4,3",
            Midend.newGame(Sixteen, "4x3", "12345").id(),
        )
        assertEquals(
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
            Midend.newGame(Sixteen, "4x4", "12345").id(),
        )
        assertEquals(
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
            Midend.newGame(Sixteen, "", "12345").id(),
        )
        assertEquals(
            "5x4:17,20,19,2,7,15,6,10,9,12,14,1,16,4,5,8,13,3,11,18",
            Midend.newGame(Sixteen, "5x4", "12345").id(),
        )
        assertEquals(
            "5x5:21,25,23,2,8,11,5,18,17,19,16,1,22,13,4,6,9,3,15,7,14,24,20,12,10",
            Midend.newGame(Sixteen, "5x5", "12345").id(),
        )

        // Placement is by rank, so the two rotations of a rectangle
        // produce the same description.
        assertEquals(
            Midend.newGame(Sixteen, "4x5", "12345").id().substringAfter(':'),
            Midend.newGame(Sixteen, "5x4", "12345").id().substringAfter(':'),
        )

        // A different seed changes the description.
        assertNotEquals(
            Midend.newGame(Sixteen, "4x4", "12345").id(),
            Midend.newGame(Sixteen, "4x4", "1").id(),
        )
    }

    @Test
    fun `descriptions use the move generator for a target game`() {
        // The retry rule draws two values per attempt, so a rejected
        // attempt still advances the stream. These vectors cover 1, 3,
        // 10, and 50 moves; `4x4m50` needs 56 attempts.
        assertEquals(
            "4x4m1:4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
            Midend.newGame(Sixteen, "4x4m1", "12345").id(),
        )
        assertEquals(
            "4x4m3:4,1,2,3,8,5,6,7,10,11,12,9,13,14,15,16",
            Midend.newGame(Sixteen, "4x4m3", "12345").id(),
        )
        assertEquals(
            "4x4m10:4,8,2,16,3,10,6,12,15,7,14,5,13,1,11,9",
            Midend.newGame(Sixteen, "4x4m10", "12345").id(),
        )
        assertEquals(
            "4x4m11:4,10,2,16,3,7,6,12,15,1,14,5,13,8,11,9",
            Midend.newGame(Sixteen, "4x4m11", "12345").id(),
        )
        assertEquals(
            "4x4m50:9,4,15,10,11,12,13,7,5,2,6,3,8,14,16,1",
            Midend.newGame(Sixteen, "4x4m50", "12345").id(),
        )
        assertEquals(
            "5x5m7:1,2,3,7,21,9,10,6,14,5,8,11,12,13,19,15,16,17,18,25,22,23,24,4,20",
            Midend.newGame(Sixteen, "5x5m7", "12345").id(),
        )
        assertEquals(
            "2x2m1:3,2,1,4",
            Midend.newGame(Sixteen, "2x2m1", "12345").id(),
        )
        assertEquals(
            "4x4m10:16,13,9,12,3,6,15,2,5,7,1,8,11,14,4,10",
            Midend.newGame(Sixteen, "4x4m10", "1").id(),
        )

        // Adjacent targets differ, so a generator that ignores the
        // target cannot pass.
        assertNotEquals(
            Midend.newGame(Sixteen, "4x4m10", "12345").id(),
            Midend.newGame(Sixteen, "4x4m11", "12345").id(),
        )
    }

    @Test
    fun `new game reads the tiles without shifting them`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        ) as Sixteen.State
        assertEquals(4, state.width)
        assertEquals(4, state.height)
        assertEquals(16, state.count)
        assertEquals(13, state.tileAt(0, 0))
        assertEquals(4, state.tileAt(3, 3))
        assertEquals(0, state.completed)
        assertEquals(0, state.moveCount)
        assertEquals(0, state.moveTarget)
        assertFalse(state.usedSolve)
        assertEquals(0, state.lastMovementSense)

        // The target is copied from the parameters into the state.
        val targeted = Sixteen.newState(
            Sixteen.decodeParams("4x4m10"),
            "4,8,2,16,3,10,6,12,15,7,14,5,13,1,11,9",
        ) as Sixteen.State
        assertEquals(10, targeted.moveTarget)

        // Leading zeros parse to the same board but keep their text.
        val plain = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            (1..16).joinToString(","),
        ) as Sixteen.State
        val padded = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            (1..16).joinToString(",") { "0$it" },
        ) as Sixteen.State
        assertEquals(plain.tiles.toList(), padded.tiles.toList())
    }

    @Test
    fun `a state copies deeply and compares on every field`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val copy = state.copy() as Sixteen.State
        assertEquals(state, copy)
        assertNotEquals(state, Sixteen.newState(Sixteen.decodeParams("4x4"), "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"))
        copy.tiles[0] = 5
        assertNotEquals(state, copy)
        assertEquals(13, (state as Sixteen.State).tiles[0])

        val base = Sixteen.newState(Sixteen.decodeParams("4x4"), "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16") as Sixteen.State
        fun variant(
            completed: Int = base.completed,
            moveCount: Int = base.moveCount,
            usedSolve: Boolean = base.usedSolve,
            moveTarget: Int = base.moveTarget,
            sense: Int = base.lastMovementSense,
        ) = Sixteen.State(
            base.width, base.height, base.tiles.copyOf(), completed, moveCount,
            usedSolve, moveTarget, sense,
        )
        assertNotEquals(base, variant(completed = 1))
        assertNotEquals(base, variant(moveCount = 1))
        assertNotEquals(base, variant(usedSolve = true))
        assertNotEquals(base, variant(moveTarget = 10))
        assertNotEquals(base, variant(sense = 1))
    }

    @Test
    fun `the text format pads to the digits of the board size`() {
        fun text(desc: String, params: String = "4x4") =
            Sixteen.textFormat(Sixteen.newState(Sixteen.decodeParams(params), desc))

        assertEquals(
            "6 8 7\n3 2 5\n4 1 9\n",
            text("6,8,7,3,2,5,4,1,9", "3x3"),
        )
        assertEquals(
            "13 15 14  3\n10 12  5  6\n 7  1  8  2\n11  9 16  4\n",
            text("13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4"),
        )
        assertEquals(
            " 1  2  3  4\n 5  6  7  8\n 9 10 11 12\n13 14 15 16\n",
            text("1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"),
        )
        assertEquals(
            "21 25 23  2  8\n11  5 18 17 19\n16  1 22 13  4\n 6  9  3 15  7\n14 24 20 12 10\n",
            text(
                "21,25,23,2,8,11,5,18,17,19,16,1,22,13,4,6,9,3,15,7,14,24,20,12,10",
                "5x5",
            ),
        )
    }

    @Test
    fun `the status text has no space after COMPLETED and a target suffix`() {
        fun open(params: String = "4x4", desc: String = "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4") =
            Midend.fromGameId(Sixteen, "$params:$desc")

        val midend = open()
        assertEquals("Moves: 0", midend.statusText())

        val targeted = open("4x4m10", "4,8,2,16,3,10,6,12,15,7,14,5,13,1,11,9")
        assertEquals("Moves: 0 (target 10)", targeted.statusText())

        val solved = Sixteen.newState(Sixteen.decodeParams("4x4"), "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16") as Sixteen.State
        assertEquals(0, Sixteen.status(solved))
        assertEquals("Moves: 0", Sixteen.statusText(solved, Sixteen.newUi(solved)))

        // A completing move: `4,1,2,3,...` with `R0,-1`.
        val almost = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
        )
        val completed = Sixteen.executeMove(almost, "R0,-1") as Sixteen.State
        assertEquals(1, Sixteen.status(completed))
        assertEquals("COMPLETED!Moves: 1", Sixteen.statusText(completed, Sixteen.newUi(completed)))

        val targetedAlmost = Sixteen.newState(
            Sixteen.decodeParams("4x4m10"),
            "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
        )
        val targetedCompleted = Sixteen.executeMove(targetedAlmost, "R0,-1") as Sixteen.State
        assertEquals(
            "COMPLETED!Moves: 1 (target 10)",
            Sixteen.statusText(targetedCompleted, Sixteen.newUi(targetedCompleted)),
        )

        // A solve line counts moves since the solve.
        val autoSolved = Sixteen.executeMove(completed, "S") as Sixteen.State
        assertEquals(1, Sixteen.status(autoSolved))
        assertEquals("Moves since auto-solve: 0", Sixteen.statusText(autoSolved, Sixteen.newUi(autoSolved)))
        val afterSolveMove = Sixteen.executeMove(autoSolved, "R0,1") as Sixteen.State
        assertEquals("Moves since auto-solve: 1", Sixteen.statusText(afterSolveMove, Sixteen.newUi(afterSolveMove)))
        val afterTwo = Sixteen.executeMove(afterSolveMove, "C0,1") as Sixteen.State
        assertEquals("Moves since auto-solve: 2", Sixteen.statusText(afterTwo, Sixteen.newUi(afterTwo)))
    }

    @Test
    fun `a move rotates one line and counts once`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        ) as Sixteen.State

        val right = Sixteen.executeMove(state, "R0,1") as Sixteen.State
        assertEquals(" 3 13 15 14\n10 12  5  6\n 7  1  8  2\n11  9 16  4\n", Sixteen.textFormat(right))
        assertEquals(1, right.moveCount)
        assertEquals(1, right.lastMovementSense)
        assertEquals(0, right.completed)

        val left = Sixteen.executeMove(state, "R0,-1") as Sixteen.State
        assertEquals("15 14  3 13\n10 12  5  6\n 7  1  8  2\n11  9 16  4\n", Sixteen.textFormat(left))
        assertEquals(-1, left.lastMovementSense)

        val down = Sixteen.executeMove(state, "C0,1") as Sixteen.State
        assertEquals("11 15 14  3\n13 12  5  6\n10  1  8  2\n 7  9 16  4\n", Sixteen.textFormat(down))

        val up = Sixteen.executeMove(state, "C0,-1") as Sixteen.State
        assertEquals("10 15 14  3\n 7 12  5  6\n11  1  8  2\n13  9 16  4\n", Sixteen.textFormat(up))

        // A move in the opposite direction restores the board but not
        // the move count.
        val back = Sixteen.executeMove(right, "R0,-1") as Sixteen.State
        assertEquals(state.tiles.toList(), back.tiles.toList())
        assertEquals(2, back.moveCount)
    }

    @Test
    fun `the loose bounds accept a full cycle and count it`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        ) as Sixteen.State

        for (move in listOf("R0,0", "C0,0", "R0,4", "R0,-4", "C0,4", "C0,-4", "R3,4", "C3,-4")) {
            val after = Sixteen.executeMove(state, move) as Sixteen.State
            assertEquals(state.tiles.toList(), after.tiles.toList(), "board after $move")
            assertEquals(1, after.moveCount, "move count after $move")
            assertNotNull(after.lastMovementSense)
        }

        // The bounds are `-h..w` for a row and `-h..h` for a column.
        assertNull(Sixteen.executeMove(state, "R0,5"))
        assertNull(Sixteen.executeMove(state, "R0,-5"))
        assertNull(Sixteen.executeMove(state, "C0,5"))
        assertNull(Sixteen.executeMove(state, "C0,-5"))
        assertNull(Sixteen.executeMove(state, "R4,0"))
        assertNull(Sixteen.executeMove(state, "C4,0"))
        assertNull(Sixteen.executeMove(state, "R-1,0"))
        assertNull(Sixteen.executeMove(state, "C-1,0"))
        assertNull(Sixteen.executeMove(state, "X0,0"))
        assertNull(Sixteen.executeMove(state, ""))
        assertNull(Sixteen.executeMove(state, "S2"))

        // `sscanf("%d,%d")` behaviour: a sign and white space before a
        // number are accepted, but the comma must follow the first
        // number exactly, and trailing junk is ignored.
        assertNotNull(Sixteen.executeMove(state, "R0,+1"))
        assertNotNull(Sixteen.executeMove(state, "R 0, +1"))
        assertNotNull(Sixteen.executeMove(state, "R0, 1"))
        assertNotNull(Sixteen.executeMove(state, "R0,1x"))
        assertNull(Sixteen.executeMove(state, "R0 ,1"))
        // Trailing junk after the second number is ignored.
        assertNotNull(Sixteen.executeMove(state, "R0,1,"))
        assertNull(Sixteen.executeMove(state, "R,1"))
        assertNull(Sixteen.executeMove(state, "R0"))

        // On a tall board `R0,-h` is more than one full cycle. For
        // 3x5, `-5` is the same rotation as `+1`.
        val tall = Sixteen.newState(
            Sixteen.decodeParams("3x5"),
            "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15",
        )
        val minusFive = Sixteen.executeMove(tall, "R0,-5") as Sixteen.State
        val plusOne = Sixteen.executeMove(tall, "R0,1") as Sixteen.State
        assertEquals(" 3  1  2\n 4  5  6\n 7  8  9\n10 11 12\n13 14 15\n", Sixteen.textFormat(minusFive))
        assertEquals(plusOne.tiles.toList(), minusFive.tiles.toList())
        assertNull(Sixteen.executeMove(tall, "R0,-6"))
    }

    @Test
    fun `a solve installs the solved board and marks the state`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        assertEquals("S", Sixteen.solve(state))

        val solved = Sixteen.executeMove(state, "S") as Sixteen.State
        assertEquals((1..16).toList(), solved.tiles.toList())
        assertEquals(1, solved.moveCount)
        assertEquals(1, solved.completed)
        assertTrue(solved.usedSolve)
        assertEquals(1, Sixteen.status(solved))
        assertEquals("Moves since auto-solve: 0", Sixteen.statusText(solved, Sixteen.newUi(solved)))

        // `S` is not a solution in the hint sense; it is a clean board.
        assertEquals(" 1  2  3  4\n 5  6  7  8\n 9 10 11 12\n13 14 15 16\n", Sixteen.textFormat(solved))
    }

    @Test
    fun `completion is detected on a real move only`() {
        val almost = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
        )
        assertEquals(0, Sixteen.status(almost))
        val completed = Sixteen.executeMove(almost, "R0,-1") as Sixteen.State
        assertEquals(1, completed.completed)
        assertEquals(1, completed.moveCount)
        assertFalse(completed.usedSolve)

        // A state that is already solved stays open until a move. A
        // full-cycle move then flags it complete without changing the
        // board.
        val solvedBoard = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16",
        ) as Sixteen.State
        assertEquals(0, Sixteen.status(solvedBoard))
        val afterNoop = Sixteen.executeMove(solvedBoard, "R0,0") as Sixteen.State
        assertEquals(1, afterNoop.completed)
        assertEquals(solvedBoard.tiles.toList(), afterNoop.tiles.toList())
    }

    @Test
    fun `changed state reports only an honest completion`() {
        val ui = Sixteen.newUi(
            Sixteen.newState(Sixteen.decodeParams("4x4"), "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"),
        )
        val open = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
        )
        val completed = Sixteen.executeMove(open, "R0,-1")
        assertTrue(Sixteen.changedState(ui, open, completed!!))
        assertFalse(Sixteen.changedState(ui, null, completed), "no old state means no event")
        assertFalse(Sixteen.changedState(ui, open, open), "an open state reports nothing")

        val solved = Sixteen.executeMove(open, "S")!!
        assertFalse(
            Sixteen.changedState(ui, open, solved),
            "a solve is not an honest completion",
        )
        assertFalse(
            Sixteen.changedState(ui, completed, open),
            "leaving a completed state is not an event",
        )
    }

    @Test
    fun `the cursor selects lines and locks`() {
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val ui = midend.ui as Sixteen.Ui
        val tile = 48

        // The first arrow key only reveals the cursor.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_UP, tile))
        assertTrue(ui.cursorVisible)
        assertEquals(0, ui.curX)
        assertEquals(0, ui.curY)
        assertEquals(1, midend.stateCount)

        // The second key walks to the top ring, and select slides the row.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_UP, tile))
        assertEquals(0, ui.curX)
        assertEquals(-1, ui.curY)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals("C0,-1", midend.serialise().values("MOVE").single())
        assertEquals(2, midend.stateCount)

        // A lock toggle is a UI update, not a move.
        val fresh = Midend.fromGameId(Sixteen, "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4")
        val freshUi = fresh.ui as Sixteen.Ui
        fresh.processKey(0, 0, Buttons.CURSOR_UP, tile)
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals(Sixteen.CursorMode.LOCK_TILE, freshUi.cursorMode)
        assertEquals(1, fresh.stateCount)
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals(Sixteen.CursorMode.UNLOCKED, freshUi.cursorMode)
        assertEquals(1, fresh.stateCount)

        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(0, 0, Buttons.CURSOR_SELECT2, tile))
        assertEquals(Sixteen.CursorMode.LOCK_POSITION, freshUi.cursorMode)
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(0, 0, Buttons.CURSOR_SELECT2, tile))
        assertEquals(Sixteen.CursorMode.UNLOCKED, freshUi.cursorMode)

        // With `lock_tile`, an arrow moves the line and the cursor
        // follows the tile.
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        assertEquals(Sixteen.CursorMode.LOCK_TILE, freshUi.cursorMode)
        assertEquals(KeyOutcome.SomeEffect, fresh.processKey(0, 0, Buttons.CURSOR_LEFT, tile))
        assertEquals("R0,-1", fresh.serialise().values("MOVE").single())
        assertEquals(3, freshUi.curX)
        assertEquals(0, freshUi.curY)

        // Shift slides without moving the cursor; control moves the
        // cursor with the tile.
        val modified = Midend.fromGameId(Sixteen, "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4")
        val modifiedUi = modified.ui as Sixteen.Ui
        modified.processKey(0, 0, Buttons.CURSOR_UP, tile)
        assertEquals(
            KeyOutcome.SomeEffect,
            modified.processKey(0, 0, Buttons.CURSOR_LEFT or Buttons.MOD_SHFT, tile),
        )
        assertEquals("R0,-1", modified.serialise().values("MOVE").single())
        assertEquals(0, modifiedUi.curX)
        assertEquals(0, modifiedUi.curY)

        assertEquals(
            KeyOutcome.SomeEffect,
            modified.processKey(0, 0, Buttons.CURSOR_DOWN or Buttons.MOD_CTRL, tile),
        )
        assertEquals("C0,1", modified.serialise().values("MOVE").last())
        assertEquals(0, modifiedUi.curX)
        assertEquals(1, modifiedUi.curY)

        // A modified cursor key on the ring is unused.
        val ring = Midend.fromGameId(Sixteen, "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4")
        ring.processKey(0, 0, Buttons.CURSOR_UP, tile)
        ring.processKey(0, 0, Buttons.CURSOR_UP, tile)
        assertEquals(
            KeyOutcome.Unused,
            ring.processKey(0, 0, Buttons.CURSOR_LEFT or Buttons.MOD_CTRL, tile),
        )
        assertEquals(1, ring.stateCount)
    }

    @Test
    fun `a keypad digit in a lock mode is the null move`() {
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val tile = 48

        // Without a lock, a keypad digit is a UI update.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_UP, tile))
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '8'.code, tile),
        )
        assertEquals(1, midend.stateCount)

        // With `lock_tile`, it produces the null move `C0,0`: one history
        // entry, one more move, and the same board.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, tile))
        val before = (midend.state as Sixteen.State).tiles.toList()
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.MOD_NUM_KEYPAD or '8'.code, tile),
        )
        assertEquals("C0,0", midend.serialise().values("MOVE").single())
        assertEquals(1, (midend.state as Sixteen.State).moveCount)
        assertEquals(before, (midend.state as Sixteen.State).tiles.toList())
        // A null move does not complete an unsolved board.
        assertEquals(0, midend.status())
    }

    @Test
    fun `the C midend modifier filter applies to the raw button`() {
        // C's `midend_process_key` rejects `CTRL` on a key that is not a
        // cursor move, and keeps `MOD_NUM_KEYPAD` only on a digit. The
        // seam passes the raw button, so the game applies both rules.
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(24, 72, Buttons.LEFT_BUTTON or Buttons.MOD_CTRL, 48),
        )
        assertEquals(1, midend.stateCount)
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(
                0,
                0,
                Buttons.MOD_CTRL or Buttons.MOD_NUM_KEYPAD or '8'.code,
                48,
            ),
        )
        assertEquals(1, midend.stateCount)

        // The same click with a keypad flag is a plain click, because the
        // flag is dropped from a button that is not a digit.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(24, 72, Buttons.LEFT_BUTTON or Buttons.MOD_NUM_KEYPAD, 48),
        )
        assertEquals("R0,-1", midend.serialise().values("MOVE").single())
    }

    @Test
    fun `the ring walk remaps the four corners`() {
        // C walks a (w+2) x (h+2) ring and maps the four corners of that
        // ring back onto the frame. A wrong transform shows up as the
        // wrong line in the following select.
        fun run(sequence: List<Int>): Pair<Sixteen.Ui, String> {
            val midend = Midend.fromGameId(
                Sixteen,
                "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
            )
            val ui = midend.ui as Sixteen.Ui
            for (key in sequence) {
                midend.processKey(0, 0, key, 48)
            }
            return ui to (midend.serialise().values("MOVE").lastOrNull() ?: "")
        }

        val cases = listOf(
            listOf(Buttons.CURSOR_UP, Buttons.CURSOR_UP, Buttons.CURSOR_LEFT) to (-1 to 0),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_UP,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
            ) to (4 to 0),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_LEFT,
            ) to (-1 to 3),
            listOf(
                Buttons.CURSOR_UP,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_DOWN,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
                Buttons.CURSOR_RIGHT,
            ) to (4 to 3),
        )
        val moves = listOf("R0,-1", "R0,1", "R3,-1", "R3,1")

        for ((index, case) in cases.withIndex()) {
            val (ui, _) = run(case.first + Buttons.CURSOR_SELECT)
            assertEquals(case.second.first, ui.curX, "cursor x after $case")
            assertEquals(case.second.second, ui.curY, "cursor y after $case")
            val (_, move) = run(case.first + Buttons.CURSOR_SELECT)
            assertEquals(moves[index], move, "move after $case")
        }
    }

    @Test
    fun `current key labels follow the C table`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val ui = Sixteen.newUi(state) as Sixteen.Ui
        assertEquals("", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.cursorVisible = true
        ui.curX = 0
        ui.curY = -1
        assertEquals("Slide", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Back", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        for (ring in listOf(0 to -1, 4 to 0, 0 to 4, -1 to 0)) {
            ui.curX = ring.first
            ui.curY = ring.second
            assertEquals("Slide", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
            assertEquals("Back", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        }

        ui.curX = 2
        ui.curY = 2
        ui.cursorMode = Sixteen.CursorMode.UNLOCKED
        assertEquals("Lock tile", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Lock pos", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.cursorMode = Sixteen.CursorMode.LOCK_TILE
        assertEquals("Unlock", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Lock pos", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.cursorMode = Sixteen.CursorMode.LOCK_POSITION
        assertEquals("Lock tile", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Unlock", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        assertEquals("", Sixteen.currentKeyLabel(ui, state, Buttons.CURSOR_UP))
        assertEquals(
            "",
            Sixteen.currentKeyLabel(ui, state, Buttons.LEFT_BUTTON),
        )
    }

    @Test
    fun `the cursor location is the raw cell coordinate`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val ui = Sixteen.newUi(state) as Sixteen.Ui
        assertNull(Sixteen.cursorLocation(ui, state, 48))

        ui.cursorVisible = true
        ui.curX = 2
        ui.curY = 3
        assertEquals(Game.CursorRect(144, 192, 48, 48), Sixteen.cursorLocation(ui, state, 48))

        // A frame position reports a rectangle on the border, not the
        // board cell.
        ui.curX = -1
        ui.curY = 0
        assertEquals(Game.CursorRect(0, 48, 48, 48), Sixteen.cursorLocation(ui, state, 48))
        ui.curX = 4
        ui.curY = -1
        assertEquals(Game.CursorRect(240, 0, 48, 48), Sixteen.cursorLocation(ui, state, 48))
        ui.curX = 0
        ui.curY = 4
        assertEquals(Game.CursorRect(48, 240, 48, 48), Sixteen.cursorLocation(ui, state, 48))
    }

    @Test
    fun `the board is the tile size times the grid plus two borders`() {
        assertEquals(Size(288, 288), Sixteen.computeSize(Sixteen.decodeParams("4x4"), 48))
        assertEquals(Size(240, 240), Sixteen.computeSize(Sixteen.decodeParams("3x3"), 48))
        assertEquals(Size(288, 240), Sixteen.computeSize(Sixteen.decodeParams("4x3"), 48))
        assertEquals(Size(396, 396), Sixteen.computeSize(Sixteen.decodeParams("4x4"), 66))
        // The border is part of the formula even for a rejected size.
        assertEquals(Size(96, 96), Sixteen.computeSize(Sixteen.decodeParams("0x0"), 48))
    }

    @Test
    fun `the palette is the default background with derived edges`() {
        val colours = Sixteen.colours()
        assertEquals(4 * 3, colours.size)
        assertEquals(
            listOf(800, 800, 800, 0, 0, 0, 967, 967, 967, 633, 633, 633),
            colours.map { (it * 1000.0f).roundToInt() },
        )
    }

    @Test
    fun `drawing covers the frame, the arrows, and the board`() {
        val state = Sixteen.newState(
            Sixteen.decodeParams("4x4"),
            "13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val ui = Sixteen.newUi(state) as Sixteen.Ui

        val plain = RecordingSink()
        Sixteen.redraw(state, ui, plain, 48)
        assertEquals(2 + 16 + 32, plain.polys.size)
        assertEquals(16, plain.updates.size)
        assertEquals(16, plain.rects.size)
        assertEquals(1, plain.clips.size)
        assertTrue(plain.polys.take(2).all { it.points.size == 10 })
        assertTrue(plain.polys.drop(2).take(16).all { it.points.size == 14 })
        assertTrue(plain.polys.drop(2).take(16).all { it.outline == 1 })

        // A visible cursor over a cell highlights its inner rectangle.
        ui.cursorVisible = true
        ui.curX = 3
        ui.curY = 2
        val cursor = RecordingSink()
        Sixteen.redraw(state, ui, cursor, 48)
        assertEquals(plain.polys.size, cursor.polys.size)
        assertEquals(1, cursor.rects.count { it.colour == 3 })
        assertEquals(15, cursor.rects.count { it.colour == 0 })

        // A cursor on the ring draws a highlighted arrow and the C
        // update rectangle, which is the cell under the cursor.
        ui.curX = 0
        ui.curY = -1
        val top = RecordingSink()
        Sixteen.redraw(state, ui, top, 48)
        assertEquals(plain.polys.size + 1, top.polys.size)
        val arrow = top.polys[2 + 16]
        assertEquals(14, arrow.points.size)
        assertEquals(2, arrow.fill)
        assertEquals(1, arrow.outline)
        assertEquals(17, top.updates.size)
        assertEquals(Update(48, 0, 48, 48), top.updates.first())

        ui.curX = -1
        ui.curY = 0
        val left = RecordingSink()
        Sixteen.redraw(state, ui, left, 48)
        assertEquals(Update(0, 48, 48, 48), left.updates.first())

        ui.curX = 0
        ui.curY = 4
        val bottom = RecordingSink()
        Sixteen.redraw(state, ui, bottom, 48)
        assertEquals(Update(48, 240, 48, 48), bottom.updates.first())

        // At tile 10 the highlight width is zero, so the triangles use
        // the full tile and the inner rectangle covers it.
        val tiny = RecordingSink()
        Sixteen.redraw(state, Sixteen.newUi(state), tiny, 10)
        assertEquals(Size(60, 60).width, Sixteen.computeSize(Sixteen.decodeParams("4x4"), 10).width)
        assertTrue(tiny.rects.all { it.w == 10 && it.h == 10 })
    }

    @Test
    fun `a click on the frame slides a line and counts one move`() {
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val tile = 48

        // The left border band of row 0.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 72, Buttons.LEFT_BUTTON, tile))
        assertEquals("R0,-1", midend.serialise().values("MOVE").single())
        assertEquals(1, (midend.state as Sixteen.State).moveCount)
        assertEquals(
            "15 14  3 13\n10 12  5  6\n 7  1  8  2\n11  9 16  4\n",
            midend.textFormat(),
        )

        // A click in a cell is a UI update with no move.
        val before = midend.stateCount
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(72, 72, Buttons.LEFT_BUTTON, tile))
        assertEquals(before, midend.stateCount)

        // A click on a frame corner is also a UI update.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 24, Buttons.LEFT_BUTTON, tile))
        assertEquals(before, midend.stateCount)

        // The right button reverses the direction.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(264, 72, Buttons.RIGHT_BUTTON, tile),
        )
        assertEquals("R0,-1", midend.serialise().values("MOVE").last())
        assertEquals(2, (midend.state as Sixteen.State).moveCount)
    }

    @Test
    fun `undo and redo restore the board and the move count`() {
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:4,1,2,3,5,6,7,8,9,10,11,12,13,14,15,16",
        )
        val tile = 48
        assertFalse(midend.canUndo)
        assertFalse(midend.takeCompletedEvent())

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(24, 72, Buttons.LEFT_BUTTON, tile))
        assertEquals(1, midend.status())
        assertEquals("COMPLETED!Moves: 1", midend.statusText())
        assertTrue(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertEquals("Moves: 0", midend.statusText())
        assertEquals(0, (midend.state as Sixteen.State).moveCount)
        assertTrue(midend.canRedo)
        assertFalse(midend.takeCompletedEvent(), "an undo leaves the completion")

        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertEquals("COMPLETED!Moves: 1", midend.statusText())
        assertTrue(midend.takeCompletedEvent(), "a redo into the completion raises the event")
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `a solve is one history entry and raises no completion event`() {
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        assertEquals("S", Sixteen.solve(midend.state))

        assertTrue(midend.solve())
        val solved = midend.state as Sixteen.State
        assertTrue(solved.usedSolve)
        assertEquals(1, solved.completed)
        assertEquals(1, solved.moveCount)
        assertEquals(1, midend.status())
        assertEquals("Moves since auto-solve: 0", midend.statusText())
        assertEquals("SOLVE", midend.serialise().records.last().key)
        assertEquals("S", midend.serialise().value("SOLVE"))
        assertFalse(midend.takeCompletedEvent(), "a solve never raises the completion event")

        // C never fails, so a second solve appends a second record.
        assertTrue(midend.solve())
        assertEquals(listOf("S", "S"), midend.serialise().values("SOLVE"))
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `undo from a solved state returns to the previous board`() {
        // C's animation path asserts on this transition and aborts the
        // oracle, so it can only be checked in Kotlin. The state itself
        // is well defined: the history is a normal undo.
        val midend = Midend.fromGameId(
            Sixteen,
            "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
        )
        val before = midend.state as Sixteen.State
        assertTrue(midend.solve())
        assertTrue(midend.undo())
        assertEquals(before, midend.state)
        assertTrue(midend.canRedo)
        assertEquals("Moves: 0", midend.statusText())
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val colour: Int)
    private data class Poly(val points: List<Int>, val fill: Int, val outline: Int)
    private data class Update(val x: Int, val y: Int, val w: Int, val h: Int)

    private class RecordingSink : DrawSink {
        val rects = ArrayList<Rect>()
        val polys = ArrayList<Poly>()
        val updates = ArrayList<Update>()
        val clips = ArrayList<Rect>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            rects += Rect(x, y, width, height, colour)
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            polys += Poly(points.toList(), fillColour, outlineColour)
        }

        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            clips += Rect(x, y, width, height, 0)
        }

        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {
            updates += Update(x, y, width, height)
        }

        override fun status(text: String) {}
    }
}
