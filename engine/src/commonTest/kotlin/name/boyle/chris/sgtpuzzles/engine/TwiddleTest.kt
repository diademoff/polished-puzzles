package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. The API edges and the quirks of the Twiddle port. */
class TwiddleTest {

    private fun params(
        width: Int,
        height: Int,
        n: Int = 2,
        rowsOnly: Boolean = false,
        orientable: Boolean = false,
        moveTarget: Int = 0,
    ) = Twiddle.Params(width, height, n, rowsOnly, orientable, moveTarget)

    private val defaultGrid = "2,5,6,7,3,1,4,9,8"

    @Test
    fun `parameters decode as C does`() {
        // The digit cursor starts at the beginning, so a sign or a space
        // stops it and the `x`, `n`, or `m` that follows is not read there.
        assertEquals(params(0, 0), Twiddle.decodeParams(""))
        assertEquals(params(3, 3), Twiddle.decodeParams("3"))
        assertEquals(params(3, 3), Twiddle.decodeParams("3x3"))
        assertEquals(params(4, 4), Twiddle.decodeParams("04x04n2"))
        assertEquals(params(3, 3), Twiddle.decodeParams("+3x3n2"))
        assertEquals(params(3, 3), Twiddle.decodeParams(" 3x3n2"))
        assertEquals(params(3, 3, rowsOnly = true, orientable = true), Twiddle.decodeParams("3x3n2rroo"))
        assertEquals(params(3, 3, rowsOnly = true), Twiddle.decodeParams("3x3n2m0r"))
        assertEquals(params(3, 3, moveTarget = 10), Twiddle.decodeParams("3x3n2m10"))
        assertEquals(params(3, 3, n = 1), Twiddle.decodeParams("3x3n1"))
        assertEquals(params(3, 0), Twiddle.decodeParams("3x"))
        assertEquals(params(3, 2), Twiddle.decodeParams("3x2n2"))
        // `m` is read before `x`, which the final loop then ignores.
        assertEquals(params(3, 3, moveTarget = 5), Twiddle.decodeParams("3m5x3"))
        // A second `x` or `n` is just skipped by the flag loop.
        assertEquals(params(3, 3), Twiddle.decodeParams("3x3n2x2"))
        assertEquals(params(3, 3), Twiddle.decodeParams("3x3n2n3"))
        assertEquals(params(3, 3, moveTarget = -1), Twiddle.decodeParams("3x3n2m-1"))
        assertEquals(params(3, 3), Twiddle.decodeParams("3x3n2m"))
    }

    @Test
    fun `encoding keeps the flags and the target in the short form`() {
        assertEquals("3x3n2", Twiddle.encodeParams(params(3, 3), true))
        assertEquals("3x3n2r", Twiddle.encodeParams(params(3, 3, rowsOnly = true), false))
        assertEquals("3x3n2o", Twiddle.encodeParams(params(3, 3, orientable = true), false))
        assertEquals("4x4n3", Twiddle.encodeParams(params(4, 4, n = 3), false))
        // `encode_params` ignores `full`, so both save parameters carry
        // `m` and so does the game id.
        assertEquals("3x3n2m10", Twiddle.encodeParams(params(3, 3, moveTarget = 10), true))
        assertEquals("3x3n2m10", Twiddle.encodeParams(params(3, 3, moveTarget = 10), false))

        val midend = Midend.newGame(Twiddle, "3x3n2m10", "12345")
        assertTrue(midend.id().startsWith("3x3n2m10:"))
        assertEquals("3x3n2m10", midend.serialise().value("PARAMS"))
        assertEquals("3x3n2m10", midend.serialise().value("CPARAMS"))
    }

    @Test
    fun `validation messages are exact and ordered`() {
        assertEquals(null, Twiddle.validateParams(params(3, 3), true))
        assertEquals(null, Twiddle.validateParams(params(3, 2), false))
        assertEquals(
            "Rotating block size must be at least two",
            Twiddle.validateParams(params(3, 3, n = 1), true),
        )
        assertEquals(
            "Width must be at least the rotating block size",
            Twiddle.validateParams(params(1, 3), true),
        )
        assertEquals(
            "Height must be at least the rotating block size",
            Twiddle.validateParams(params(3, 1), true),
        )
        assertEquals(
            "Height must be at least the rotating block size",
            Twiddle.validateParams(params(3, 0), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Twiddle.validateParams(params(46341, 46341), true),
        )
        assertEquals(
            "Number of shuffling moves may not be negative",
            Twiddle.validateParams(params(3, 3, moveTarget = -1), true),
        )
        // The largest accepted square board. Do not build a state for it.
        assertNull(Twiddle.validateParams(params(46340, 46340), true))
    }

    @Test
    fun `presets, identity, keys, and size`() {
        assertEquals(
            listOf(
                "3x3 rows only",
                "3x3 normal",
                "3x3 orientable",
                "4x4 normal",
                "4x4 orientable",
                "4x4, rotating 3x3 blocks",
                "5x5, rotating 3x3 blocks",
                "6x6, rotating 4x4 blocks",
            ),
            Twiddle.presets().map { it.title },
        )
        assertEquals(
            listOf(
                "3x3n2r",
                "3x3n2",
                "3x3n2o",
                "4x4n2",
                "4x4n2o",
                "4x4n3",
                "5x5n3",
                "6x6n4",
            ),
            Twiddle.presets().map { it.params },
        )
        assertEquals("Twiddle", Twiddle.name)
        assertEquals("twiddle", Twiddle.backendName)
        assertEquals(48, Twiddle.preferredTileSize)
        assertTrue(Twiddle.wantsStatusBar)
        assertTrue(Twiddle.canSolve)

        // `game_request_keys` is NULL, so the midend's default arrow mode
        // applies and there are no extra labels.
        val keys = Twiddle.requestKeys(Twiddle.defaultParams())
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, keys.arrowMode)
        assertTrue(keys.labels.isEmpty())

        // `2 * (t / 2)` is not `t` when the tile size is odd.
        assertEquals(Size(3 * 63 + 62, 3 * 63 + 62), Twiddle.computeSize(params(3, 3), 63))
        assertEquals(Size(3 * 103 + 102, 3 * 103 + 102), Twiddle.computeSize(params(3, 3), 103))
        assertEquals(Size(4 * 65 + 64, 4 * 65 + 64), Twiddle.computeSize(params(4, 4), 65))
    }

    @Test
    fun `the default description is the frozen oracle value`() {
        val midend = Midend.newGame(Twiddle, "", "12345")
        assertEquals("3x3n2", Twiddle.encodeParams(midend.currentParams, false))
        assertEquals("3x3n2:$defaultGrid", midend.id())
        assertEquals(defaultGrid, midend.desc)
        // This game never produces AUXINFO and there is no UI record.
        assertNull(midend.serialise().value("AUXINFO"))
        assertNull(midend.serialise().value("UI"))
        assertNull(Twiddle.newDesc(params(3, 3), RandomState.fromSeed("12345")).auxInfo)
    }

    @Test
    fun `the state keeps the orientation and the duplicate row numbers`() {
        val oriented = Twiddle.newState(
            params(3, 3, orientable = true),
            "2r5l6r7l3d1l4l9l8r",
        ) as Twiddle.State
        assertEquals(4 * 2 + 3, oriented.grid[0])
        assertEquals(4 * 5 + 1, oriented.grid[1])
        assertEquals(4 * 3 + 2, oriented.grid[4])
        assertEquals(3, oriented.orientationAt(0, 0))
        assertEquals(2, oriented.orientationAt(1, 1))

        val rows = Twiddle.newState(params(3, 3, rowsOnly = true), "1,2,2,3,1,1,2,3,3") as Twiddle.State
        assertEquals(listOf(1, 2, 2, 3, 1, 1, 2, 3, 3), rows.grid.map { it / 4 })

        // A description of the wrong width fails the caller's validation,
        // not `newState`; the parse itself only reads one character after
        // each number.
        val copy = oriented.copy() as Twiddle.State
        assertEquals(oriented, copy)
        copy.grid[0] = 0
        assertNotEquals(oriented, copy)
    }

    @Test
    fun `descriptions are validated with the C messages`() {
        val plain = params(3, 3)
        val oriented = params(3, 3, orientable = true)
        val alsoOriented = params(3, 3, rowsOnly = true, orientable = true)

        assertEquals(null, Twiddle.validateDesc(plain, defaultGrid))
        assertEquals(null, Twiddle.validateDesc(plain, "01,02,03,04,05,06,07,08,09"))
        assertEquals(
            "Not enough numbers in string",
            Twiddle.validateDesc(plain, ""),
        )
        assertEquals(
            "Not enough numbers in string",
            Twiddle.validateDesc(plain, " 2,5,6,7,3,1,4,9,8"),
        )
        assertEquals(
            "Expected comma after number",
            Twiddle.validateDesc(plain, "2;5;6;7;3;1;4;9;8"),
        )
        assertEquals(
            "Excess junk at end of string",
            Twiddle.validateDesc(plain, defaultGrid + ","),
        )
        assertEquals(
            "Expected comma after number",
            Twiddle.validateDesc(plain, "1,2"),
        )

        // Orientable descriptions require the letter, and the last letter
        // makes the trailing-junk branch unreachable. Upstream accepts
        // junk here; this is an upstream defect, not a deviation.
        assertEquals(null, Twiddle.validateDesc(oriented, "1u2u3u4u5u6u7u8u9u"))
        assertEquals(
            "Expected orientation letter after number",
            Twiddle.validateDesc(oriented, "1u2u3u4u5u6u7u8u9"),
        )
        assertEquals(null, Twiddle.validateDesc(oriented, "1u2u3u4u5u6u7u8u9uJUNK"))
        assertEquals(null, Twiddle.validateDesc(alsoOriented, "1u2d3u4u5u6u7u8u9u"))
    }

    @Test
    fun `the move grammar follows sscanf`() {
        val state = Twiddle.newState(params(3, 3), defaultGrid)
        val accepted = listOf(
            "M0,0,1",
            "M 0,0,1",
            "M0, 0,1",
            "M+0,+0,+1",
            "M0,0,1junk",
            "M0,0,1,2",
        )
        for (move in accepted) {
            assertEquals(1, (Twiddle.executeMove(state, move) as Twiddle.State).moveCount, move)
        }
        val rejected = listOf(
            "M0 ,0,1",
            "M0,0 ,1",
            "M0,0",
            "M-1,0,1",
            "M3,0,1",
            "s",
            "S ",
            "",
        )
        for (move in rejected) {
            assertNull(Twiddle.executeMove(state, move), move)
        }

        // A direction that is a multiple of four changes no cell but still
        // counts as a move and keeps the raw direction for the animation.
        val noop = Twiddle.executeMove(state, "M0,0,100") as Twiddle.State
        assertEquals((state as Twiddle.State).grid.toList(), noop.grid.toList())
        assertEquals(1, noop.moveCount)
        assertEquals(100, noop.lastR)
        assertEquals(0, noop.lastX)
        assertEquals(0, noop.lastY)
    }

    @Test
    fun `a rotation uses the coset formula and moves the top-right cell to the top-left`() {
        val state = Twiddle.newState(params(3, 3), defaultGrid)
        val after = Twiddle.executeMove(state, "M0,0,1") as Twiddle.State
        assertEquals("5,3,6,2,7,1,4,9,8", after.grid.map { it / 4 }.joinToString(","))
        assertEquals(1, after.moveCount)
        assertEquals(0, after.lastX)
        assertEquals(0, after.lastY)
        assertEquals(1, after.lastR)

        // The raw direction is kept; only its low two bits rotate the grid.
        val backwards = Twiddle.executeMove(state, "M0,0,-1") as Twiddle.State
        assertEquals("7,2,6,3,5,1,4,9,8", backwards.grid.map { it / 4 }.joinToString(","))
        assertEquals(-1, backwards.lastR)

        // The 3x3 loop leaves the centre alone, so it keeps its number.
        val big = Twiddle.newState(params(5, 5, n = 3), "15,4,11,18,1,16,17,6,3,22,19,12,5,10,13,24,9,20,7,14,21,8,23,2,25")
        val rotated = Twiddle.executeMove(big, "M0,0,1") as Twiddle.State
        assertEquals("11,6,5,18,1,4,17,12,3,22,15,16,19,10,13,24,9,20,7,14,21,8,23,2,25",
            rotated.grid.map { it / 4 }.joinToString(","))
        assertEquals(17, rotated.grid[6] / 4)
    }

    @Test
    fun `orientation follows the rotation, including the centre of an odd block`() {
        val state = Twiddle.newState(
            params(3, 3, orientable = true),
            "2r5l6r7l3d1l4l9l8r",
        )
        val after = Twiddle.executeMove(state, "M0,0,1") as Twiddle.State
        assertEquals("5v 3> 6>\n2^ 7v 1<\n4< 9< 8>\n", Twiddle.textFormat(after))

        // The centre cell of an odd region turns by the same rule.
        val big = Twiddle.newState(
            params(5, 5, n = 3, orientable = true),
            "15u4u11d18d1d16d17d6d3r22r19r12r5l10u13l24l9r20d7u14l21d8d23d2d25r",
        )
        val rotated = Twiddle.executeMove(big, "M0,0,1") as Twiddle.State
        assertEquals(17, rotated.grid[6] / 4)
        assertEquals(3, rotated.grid[6] and 3)
    }

    @Test
    fun `solve sorts the grid, clears orientation, and sets the counters`() {
        val state = Twiddle.newState(
            params(3, 3, orientable = true),
            "2r5l6r7l3d1l4l9l8r",
        )
        assertEquals("S", Twiddle.solve(state))
        val solved = Twiddle.executeMove(state, "S") as Twiddle.State
        assertEquals((1..9).toList(), solved.grid.map { it / 4 })
        assertTrue(solved.grid.all { it and 3 == 0 })
        assertTrue(solved.usedSolve)
        assertEquals(1, solved.completed)
        assertEquals(1, solved.moveCount)
        // A SOLVE record leaves the last rotation untouched.
        assertEquals((state as Twiddle.State).lastX, solved.lastX)
        assertEquals((state as Twiddle.State).lastR, solved.lastR)
    }

    @Test
    fun `the eight-move completion and its status text`() {
        val moves = listOf(
            "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
            "M0,0,-1", "M0,1,-1", "M1,0,-1", "M0,1,-1",
        )
        var state: Game.State = Twiddle.newState(params(3, 3), defaultGrid)
        for ((i, move) in moves.withIndex()) {
            val next = Twiddle.executeMove(state, move) as Twiddle.State
            assertEquals(if (i == 7) 8 else 0, next.completed, "after $i moves")
            state = next
        }
        assertEquals(1, Twiddle.status(state))
        assertEquals("COMPLETED! Moves: 8", Twiddle.statusText(state, Twiddle.newUi(state)))
        assertEquals("1 2 3\n4 5 6\n7 8 9\n", Twiddle.textFormat(state))

        // A further move keeps the winning move number.
        val later = Twiddle.executeMove(state, "M0,0,1") as Twiddle.State
        assertEquals(8, later.completed)
        assertEquals(9, later.moveCount)
        assertEquals("COMPLETED! Moves: 8", Twiddle.statusText(later, Twiddle.newUi(later)))
    }

    @Test
    fun `an already sorted description completes only after a move`() {
        // This is an upstream quirk: new_game sets completed to zero even
        // for a sorted board.
        val sorted = Twiddle.newState(
            params(3, 3),
            "1,2,3,4,5,6,7,8,9",
        )
        assertEquals(0, Twiddle.status(sorted))
        assertEquals("Moves: 0", Twiddle.statusText(sorted, Twiddle.newUi(sorted)))

        val noop = Twiddle.executeMove(sorted, "M0,0,4") as Twiddle.State
        assertEquals(1, Twiddle.status(noop))
        assertEquals("COMPLETED! Moves: 1", Twiddle.statusText(noop, Twiddle.newUi(noop)))
    }

    @Test
    fun `the target suffix appears only on a target game`() {
        val target = Twiddle.newState(params(3, 3, moveTarget = 10), defaultGrid)
        assertEquals("Moves: 0 (target 10)", Twiddle.statusText(target, Twiddle.newUi(target)))
        val moved = Twiddle.executeMove(target, "M0,0,1") as Twiddle.State
        assertEquals("Moves: 1 (target 10)", Twiddle.statusText(moved, Twiddle.newUi(moved)))

        // A solve has its own line and no target suffix.
        val solved = Twiddle.executeMove(target, "S") as Twiddle.State
        assertEquals("Moves since auto-solve: 0", Twiddle.statusText(solved, Twiddle.newUi(solved)))
        val later = Twiddle.executeMove(solved, "M0,0,1") as Twiddle.State
        assertEquals("Moves since auto-solve: 1", Twiddle.statusText(later, Twiddle.newUi(later)))
    }

    @Test
    fun `text format pads to the widest number`() {
        val small = Twiddle.newState(params(3, 3), defaultGrid)
        assertEquals("2 5 6\n7 3 1\n4 9 8\n", Twiddle.textFormat(small))

        val oriented = Twiddle.newState(
            params(3, 3, orientable = true),
            "2r5l6r7l3d1l4l9l8r",
        )
        assertEquals("2> 5< 6>\n7< 3v 1<\n4< 9< 8>\n", Twiddle.textFormat(oriented))

        val rows = Twiddle.newState(params(3, 3, rowsOnly = true), "1,2,2,3,1,1,2,3,3")
        assertEquals("1 2 2\n3 1 1\n2 3 3\n", Twiddle.textFormat(rows))

        val big = Twiddle.newState(
            params(5, 5, n = 3, orientable = true),
            "15u4u11d18d1d16d17d6d3r22r19r12r5l10u13l24l9r20d7u14l21d8d23d2d25r",
        )
        assertEquals(
            "15^  4^ 11v 18v  1v\n" +
                "16v 17v  6v  3> 22>\n" +
                "19> 12>  5< 10^ 13<\n" +
                "24<  9> 20v  7^ 14<\n" +
                "21v  8v 23v  2v 25>\n",
            Twiddle.textFormat(big),
        )

        val wide = Twiddle.newState(
            params(6, 6, n = 4),
            "7,10,11,9,17,21,13,14,34,36,15,3,27,6,25,35,18,23,22,28,31,16,20,33,5,29,12,8,24,30,1,26,2,32,19,4",
        )
        assertEquals(
            " 7 10 11  9 17 21\n" +
                "13 14 34 36 15  3\n" +
                "27  6 25 35 18 23\n" +
                "22 28 31 16 20 33\n" +
                " 5 29 12  8 24 30\n" +
                " 1 26  2 32 19  4\n",
            Twiddle.textFormat(wide),
        )
    }

    @Test
    fun `changed state reports a completion but never a solve`() {
        val ui = Twiddle.newUi(Twiddle.newState(params(3, 3), defaultGrid))
        val start = Twiddle.newState(params(3, 3), defaultGrid)
        val moves = listOf(
            "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
            "M0,0,-1", "M0,1,-1", "M1,0,-1",
        )
        var state: Twiddle.State = start as Twiddle.State
        for (move in moves) {
            state = Twiddle.executeMove(state, move) as Twiddle.State
        }
        val completing = Twiddle.executeMove(state, "M0,1,-1") as Twiddle.State
        assertTrue(Twiddle.changedState(ui, state, completing))
        assertFalse(Twiddle.changedState(ui, null, completing))
        assertFalse(Twiddle.changedState(ui, completing, completing))

        val solved = Twiddle.executeMove(state, "S") as Twiddle.State
        assertFalse(Twiddle.changedState(ui, state, solved))
    }

    @Test
    fun `the midend raises one completion event and none for a solve`() {
        // Build the history from a save so the clicks are not part of
        // this test.
        val moves = listOf(
            "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
            "M0,0,-1", "M0,1,-1", "M1,0,-1",
        )
        val save = saveWithMoves("3x3n2", defaultGrid, moves)
        val last = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        assertFalse(last.takeCompletedEvent())
        assertEquals(
            KeyOutcome.SomeEffect,
            last.processKey(48, 120, Buttons.RIGHT_BUTTON, 48),
        )
        assertTrue(last.takeCompletedEvent(), "the last move completes the board")
        assertFalse(last.takeCompletedEvent(), "the event is consumed once")

        val solver = Midend.fromSave(
            Twiddle,
            SgtpSave.parse(saveWithMoves("3x3n2", defaultGrid, emptyList())),
        )
        assertTrue(solver.solve())
        assertFalse(solver.takeCompletedEvent(), "a solve never raises the completion event")
        assertEquals(1, solver.status())
        assertEquals("Moves since auto-solve: 0", solver.statusText())
    }

    @Test
    fun `undo and redo across a completion keep the flags`() {
        val save = saveWithMoves("3x3n2", defaultGrid, emptyList())
        val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        val moves = listOf(
            "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
            "M0,0,-1", "M0,1,-1", "M1,0,-1", "M0,1,-1",
        )
        for (move in moves) {
            val outcome = when (move) {
                "M0,0,1" -> midend.processKey(48, 72, Buttons.LEFT_BUTTON, 48)
                "M0,1,1" -> midend.processKey(48, 120, Buttons.LEFT_BUTTON, 48)
                "M1,1,1" -> midend.processKey(96, 120, Buttons.LEFT_BUTTON, 48)
                "M1,0,-1" -> midend.processKey(96, 72, Buttons.RIGHT_BUTTON, 48)
                "M0,0,-1" -> midend.processKey(48, 72, Buttons.RIGHT_BUTTON, 48)
                "M0,1,-1" -> midend.processKey(48, 120, Buttons.RIGHT_BUTTON, 48)
                else -> error("unexpected move $move")
            }
            assertEquals(KeyOutcome.SomeEffect, outcome, move)
        }
        assertEquals(1, midend.status())
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
        assertEquals("COMPLETED! Moves: 8", midend.statusText())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertTrue(midend.canRedo)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
    }

    @Test
    fun `an undo out of a restart into a completed state raises the event`() {
        // Restarting after a completion appends a RESTART entry, and an
        // undo back to the completed entry is a completion transition
        // with the old state not complete.
        val save = saveWithMoves(
            "3x3n2",
            defaultGrid,
            listOf(
                "M0,0,1", "M0,0,1", "M1,1,1", "M0,1,1",
                "M0,0,-1", "M0,1,-1", "M1,0,-1", "M0,1,-1",
            ),
        )
        val midend = Midend.fromSave(Twiddle, SgtpSave.parse(save))
        assertEquals(1, midend.status())
        midend.takeCompletedEvent()
        assertTrue(midend.restart())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.undo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
    }

    @Test
    fun `real clicks are accepted only in the band around a region centre`() {
        val state = Twiddle.newState(params(3, 3), defaultGrid)

        fun click(ui: Twiddle.Ui, x: Int, y: Int, tile: Int, button: Int = Buttons.LEFT_BUTTON) =
            Twiddle.interpretMove(state, ui, x, y, button, tile)

        // Tile 48, 63, and 103 and n = 2: the bands start at
        // (k+1)*t - t/2 + 1 and the rest is inert. The y coordinate is
        // the first accepted pixel, so the x band is the only variable.
        for ((tile, edges) in listOf(
            48 to listOf(47, 48, 95, 96, 143, 144),
            63 to listOf(61, 62, 124, 125, 187, 188),
            103 to listOf(101, 102, 204, 205, 307, 308),
        )) {
            val expected = listOf(
                MoveResult.Unused,
                MoveResult.Move("M0,0,1"),
                MoveResult.Move("M0,0,1"),
                MoveResult.Move("M1,0,1"),
                MoveResult.Move("M1,0,1"),
                MoveResult.Unused,
            )
            for ((i, x) in edges.withIndex()) {
                // A row in the first band.
                assertEquals(expected[i], click(Twiddle.Ui(), x, edges[1], tile), "tile $tile x $x")
            }
        }

        // n = 3 at tile 48 on 4x4: the first band starts at 72 and the
        // last accepted pixel is 167; 168 to 239 is inert.
        val four = Twiddle.newState(params(4, 4, n = 3), "3,13,1,15,12,8,7,6,16,4,9,5,2,14,10,11")
        fun click4(x: Int, y: Int) =
            Twiddle.interpretMove(four, Twiddle.Ui(), x, y, Buttons.LEFT_BUTTON, 48)
        assertEquals(MoveResult.Unused, click4(71, 72))
        assertEquals(MoveResult.Move("M0,0,1"), click4(72, 72))
        assertEquals(MoveResult.Move("M0,0,1"), click4(119, 119))
        assertEquals(MoveResult.Move("M1,0,1"), click4(120, 72))
        assertEquals(MoveResult.Move("M1,0,1"), click4(167, 119))
        assertEquals(MoveResult.Unused, click4(168, 72))

        // The right button reverses the direction; the middle button is
        // not a game key.
        val ui = Twiddle.Ui()
        assertEquals(MoveResult.Move("M0,0,-1"), click(ui, 72, 72, 48, Buttons.RIGHT_BUTTON))
        assertEquals(MoveResult.Unused, click(ui, 72, 72, 48, Buttons.MIDDLE_BUTTON))
    }

    @Test
    fun `an inert click keeps the cursor visible, a valid click hides it`() {
        val state = Twiddle.newState(params(3, 3), defaultGrid)

        val inert = Twiddle.Ui()
        inert.cursorVisible = true
        inert.curX = 0
        inert.curY = 0
        // (0,0) maps to FROMCOORD(-24), which is negative.
        assertEquals(
            MoveResult.Unused,
            Twiddle.interpretMove(state, inert, 0, 0, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(inert.cursorVisible, "an inert click does not hide the cursor")
        assertEquals(
            MoveResult.Move("M0,0,1"),
            Twiddle.interpretMove(state, inert, 0, 0, Buttons.CURSOR_SELECT, 48),
        )

        val valid = Twiddle.Ui()
        valid.cursorVisible = true
        assertEquals(
            MoveResult.Move("M0,0,1"),
            Twiddle.interpretMove(state, valid, 72, 72, Buttons.LEFT_BUTTON, 48),
        )
        assertFalse(valid.cursorVisible, "a valid click hides the cursor")
        assertEquals(
            MoveResult.UiUpdate,
            Twiddle.interpretMove(state, valid, 0, 0, Buttons.CURSOR_SELECT, 48),
        )
        assertTrue(valid.cursorVisible)
    }

    @Test
    fun `the keyboard corners, the keypad edges, and the cursor keys`() {
        val three = Twiddle.newState(params(3, 3), defaultGrid)
        fun key3(ui: Twiddle.Ui, button: Int) =
            Twiddle.interpretMove(three, ui, 0, 0, button, 48)

        for ((button, expected) in listOf(
            'a'.code to "M0,0,1",
            'A'.code to "M0,0,-1",
            'b'.code to "M1,0,1",
            'B'.code to "M1,0,-1",
            'c'.code to "M0,1,1",
            'C'.code to "M0,1,-1",
            'd'.code to "M1,1,1",
            'D'.code to "M1,1,-1",
            Buttons.MOD_NUM_KEYPAD + '7'.code to "M0,0,1",
            Buttons.MOD_NUM_KEYPAD + '9'.code to "M1,0,1",
            Buttons.MOD_NUM_KEYPAD + '1'.code to "M0,1,1",
            Buttons.MOD_NUM_KEYPAD + '3'.code to "M1,1,1",
        )) {
            assertEquals(MoveResult.Move(expected), key3(Twiddle.Ui(), button), "button $button")
        }

        // `w - n` and `h - n` are both odd on 3x3n2, so every keypad edge
        // and the centre falls through to "unused".
        for (digit in listOf('8', '2', '4', '6', '5')) {
            assertEquals(
                MoveResult.Unused,
                key3(Twiddle.Ui(), Buttons.MOD_NUM_KEYPAD + digit.code),
                "keypad $digit",
            )
        }
        assertEquals(MoveResult.Unused, key3(Twiddle.Ui(), '8'.code))

        // On 4x4n2 the edges and the centre exist.
        val four = Twiddle.newState(
            params(4, 4),
            "10,8,14,4,3,9,15,1,11,5,13,6,12,7,2,16",
        )
        fun key4(button: Int) = Twiddle.interpretMove(four, Twiddle.Ui(), 0, 0, button, 48)
        for ((digit, expected) in listOf(
            '8' to "M1,0,1",
            '2' to "M1,2,1",
            '4' to "M0,1,1",
            '6' to "M2,1,1",
            '5' to "M1,1,1",
        )) {
            assertEquals(
                MoveResult.Move(expected),
                key4(Buttons.MOD_NUM_KEYPAD + digit.code),
                "keypad $digit",
            )
        }

        // The cursor starts hidden. A cursor key shows it; a select on a
        // hidden cursor shows it without a move.
        val ui = Twiddle.newUi(three) as Twiddle.Ui
        assertEquals(MoveResult.UiUpdate, key3(ui, Buttons.CURSOR_UP))
        assertTrue(ui.cursorVisible)
        assertEquals(0, ui.curX)
        assertEquals(0, ui.curY)
        assertEquals(MoveResult.Move("M0,0,1"), key3(ui, Buttons.CURSOR_SELECT))
        assertEquals(MoveResult.Move("M0,0,-1"), key3(ui, Buttons.CURSOR_SELECT2))

        val fresh = Twiddle.Ui()
        assertEquals(MoveResult.UiUpdate, key3(fresh, Buttons.CURSOR_SELECT))
        assertTrue(fresh.cursorVisible)

        // A modified cursor key is still a cursor move, because entry
        // strips both modifiers.
        val shifted = Twiddle.Ui()
        assertEquals(
            MoveResult.UiUpdate,
            key3(shifted, Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT),
        )
        assertEquals(1, shifted.curX)
        assertEquals(
            MoveResult.Move("M1,0,1"),
            key3(shifted, Buttons.CURSOR_SELECT),
        )

        // The cursor clamps and a clamped move is a no-effect key.
        val atEdge = Twiddle.Ui()
        atEdge.cursorVisible = true
        atEdge.curX = 1
        atEdge.curY = 1
        assertEquals(MoveResult.NoEffect, key3(atEdge, Buttons.CURSOR_RIGHT))
        assertEquals(MoveResult.NoEffect, key3(atEdge, Buttons.CURSOR_DOWN))
        assertEquals(MoveResult.UiUpdate, key3(atEdge, Buttons.CURSOR_LEFT))
        assertEquals(0, atEdge.curX)
    }

    @Test
    fun `the cursor location covers a whole rotation region`() {
        val state = Twiddle.newState(params(4, 4, n = 3), "3,13,1,15,12,8,7,6,16,4,9,5,2,14,10,11")
        val ui = Twiddle.newUi(state) as Twiddle.Ui
        assertNull(Twiddle.cursorLocation(ui, state, 48))
        ui.cursorVisible = true
        ui.curX = 1
        ui.curY = 0
        assertEquals(Game.CursorRect(72, 24, 144, 144), Twiddle.cursorLocation(ui, state, 48))
    }

    @Test
    fun `the current key label needs a visible cursor`() {
        val state = Twiddle.newState(params(3, 3), defaultGrid)
        val ui = Twiddle.newUi(state) as Twiddle.Ui
        assertEquals("", Twiddle.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.cursorVisible = true
        assertEquals("Turn left", Twiddle.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Turn right", Twiddle.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        assertEquals("", Twiddle.currentKeyLabel(ui, state, 'a'.code))
    }

    @Test
    fun `the palette matches the frozen trace`() {
        val expected = listOf(
            intArrayOf(800, 800, 800),
            intArrayOf(0, 0, 0),
            intArrayOf(967, 967, 967),
            intArrayOf(880, 880, 880),
            intArrayOf(633, 633, 633),
            intArrayOf(720, 720, 720),
            intArrayOf(800, 400, 400),
            intArrayOf(480, 240, 240),
        )
        val colours = Twiddle.colours()
        assertEquals(8 * 3, colours.size)
        for (i in expected.indices) {
            for (channel in 0 until 3) {
                assertEquals(
                    expected[i][channel],
                    (colours[i * 3 + channel] * 1000.0f).roundToInt(),
                    "colour $i channel $channel",
                )
            }
        }

        // The shell background enters through the overload.
        val dark = Twiddle.colours(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertEquals(0.2f, dark[0], 0.0001f)
        assertEquals(0.4f, dark[1], 0.0001f)
        assertEquals(0.6f, dark[2], 0.0001f)
        assertEquals(0.2f, dark[6 * 3], 0.0001f)
        assertEquals(0.12f, dark[7 * 3], 0.0001f)
    }

    private fun saveWithMoves(params: String, desc: String, moves: List<String>): String {
        val stateCount = 1 + moves.size
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Twiddle.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", stateCount.toString()),
            SaveRecord("STATEPOS", stateCount.toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }
}
