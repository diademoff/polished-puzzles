package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: pegs. Engine-only rules with no oracle call.
 *
 * The parity module repeats the checks that need the oracle. These tests
 * pin the parameter quirks, the state ownership, the text format, the
 * key label, and the generator's retry behavior, which the oracle
 * cannot expose.
 */
class PegsTest {

    @Test
    fun `the default parameters are a 7x7 cross`() {
        val params = Pegs.defaultParams() as Pegs.Params
        assertEquals(7, params.width)
        assertEquals(7, params.height)
        assertEquals(Pegs.Type.CROSS, params.type)
        assertEquals("7x7cross", params.fullText)
        assertEquals("7x7", params.shortText)
    }

    @Test
    fun `the nine presets carry the exact titles and full parameters`() {
        val expected = listOf(
            Triple("Cross 5x7", "5x7cross", Pegs.Type.CROSS),
            Triple("Cross 7x7", "7x7cross", Pegs.Type.CROSS),
            Triple("Cross 5x9", "5x9cross", Pegs.Type.CROSS),
            Triple("Cross 7x9", "7x9cross", Pegs.Type.CROSS),
            Triple("Cross 9x9", "9x9cross", Pegs.Type.CROSS),
            Triple("Octagon", "7x7octagon", Pegs.Type.OCTAGON),
            Triple("Random 5x5", "5x5random", Pegs.Type.RANDOM),
            Triple("Random 7x7", "7x7random", Pegs.Type.RANDOM),
            Triple("Random 9x9", "9x9random", Pegs.Type.RANDOM),
        )
        val actual = Pegs.presets()
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].first, actual[i].title, "title $i")
            assertEquals(expected[i].second, actual[i].params, "params $i")
            val decoded = Pegs.decodeParams(actual[i].params) as Pegs.Params
            assertEquals(expected[i].third, decoded.type, "type $i")
            assertEquals(actual[i].params, decoded.fullText, "round trip $i")
        }
    }

    @Test
    fun `decode_params keeps the C scanner quirks`() {
        val cases = listOf(
            "7x7" to Pegs.Params(7, 7, Pegs.Type.CROSS),
            "7x7random" to Pegs.Params(7, 7, Pegs.Type.RANDOM),
            "7random" to Pegs.Params(7, 7, Pegs.Type.RANDOM),
            "5random" to Pegs.Params(5, 5, Pegs.Type.RANDOM),
            "7x7octagon" to Pegs.Params(7, 7, Pegs.Type.OCTAGON),
            "7" to Pegs.Params(7, 7, Pegs.Type.CROSS),
            "7x" to Pegs.Params(7, 0, Pegs.Type.CROSS),
            "cross" to Pegs.Params(0, 0, Pegs.Type.CROSS),
            "" to Pegs.Params(0, 0, Pegs.Type.CROSS),
            "+7x7" to Pegs.Params(7, 7, Pegs.Type.CROSS),
            "7X7" to Pegs.Params(7, 7, Pegs.Type.CROSS),
            "7x7randomjunk" to Pegs.Params(7, 7, Pegs.Type.CROSS),
            "7x7random " to Pegs.Params(7, 7, Pegs.Type.CROSS),
            "7x7Cross" to Pegs.Params(7, 7, Pegs.Type.CROSS),
        )
        for ((text, params) in cases) {
            assertEquals(params, Pegs.decodeParams(text), "decode '$text'")
        }

        // The type scan compares the whole remaining string.
        assertEquals(
            Pegs.Type.CROSS,
            (Pegs.decodeParams("7x7random ") as Pegs.Params).type,
            "a trailing space blocks the match",
        )
        assertEquals(
            Pegs.Type.CROSS,
            (Pegs.decodeParams("7x7randomjunk") as Pegs.Params).type,
            "a prefix is not a match",
        )
    }

    @Test
    fun `the short form drops the type and the full form restores it`() {
        assertEquals(
            "5x5",
            Pegs.encodeParams(Pegs.Params(5, 5, Pegs.Type.RANDOM), false),
        )
        // The short form decodes over the defaults, so the type becomes
        // Cross; a full encoding of that result says `5x5cross`.
        val shortDecoded = Pegs.decodeParams(
            Pegs.encodeParams(Pegs.Params(5, 5, Pegs.Type.RANDOM), false),
        )
        assertEquals("5x5cross", Pegs.encodeParams(shortDecoded, true))
        // `5x5` decodes as Cross, so it is invalid in full mode. That is
        // the asymmetry behind the `5x5random` save hole.
        assertNull(Pegs.validateParams(Pegs.decodeParams("5x5"), false))
        assertEquals(
            "This board type is only supported at 5x7, 5x9, 7x7, 7x9, and 9x9",
            Pegs.validateParams(Pegs.decodeParams("5x5"), true),
        )
        assertEquals(
            "5x5random",
            Pegs.encodeParams(Pegs.decodeParams("5x5random"), true),
        )
    }

    @Test
    fun `validate_params follows the C order and messages`() {
        fun full(text: String) = Pegs.validateParams(Pegs.decodeParams(text), true)
        fun short(text: String) = Pegs.validateParams(Pegs.decodeParams(text), false)

        val tooSmall = "Width and height must both be greater than three"
        val atLeastOne = "Width and height must both be at least one"
        val tooLarge = "Width times height must not be unreasonably large"
        val cross = "This board type is only supported at 5x7, 5x9, 7x7, 7x9, and 9x9"
        val octagon = "This board type is only supported at 7x7"

        assertEquals(tooSmall, full("1x1cross"))
        assertEquals(tooSmall, full("3x3cross"))
        assertEquals(tooSmall, full("0x0"))
        assertEquals(tooSmall, full("7x"))
        assertEquals(tooSmall, full("cross"))
        assertEquals(tooLarge, full("65536x65536"))
        assertEquals(cross, full("5x5"))
        assertEquals(cross, full("4x4cross"))
        assertEquals(cross, full("6x6cross"))
        assertEquals(octagon, full("5x5octagon"))
        assertEquals(octagon, full("7x9octagon"))

        // Random has no shape rule, so only the size rules apply.
        assertNull(full("4x4random"))
        assertNull(full("10x10random"))
        // The Cross list is symmetric and accepts more sizes than the
        // message names.
        for (accepted in listOf("9x5", "5x9", "9x9", "7x5", "5x7", "9x7", "7x9", "7x7")) {
            assertNull(full("${accepted}cross"), "accepted '$accepted'")
        }
        // A short parameter string only checks the sizes.
        assertNull(short("5x5"))
        assertNull(short("4x4octagon"))
        assertEquals(atLeastOne, short("0x5"))
        assertEquals(atLeastOne, short("5x0"))
        assertEquals(tooLarge, short("65536x65536"))
    }

    @Test
    fun `validate_desc uses the C order`() {
        val params = Pegs.decodeParams("3x3random")
        assertEquals(
            "Game description is wrong length",
            Pegs.validateDesc(params, "PPP"),
        )
        assertEquals(
            "Invalid character in game description",
            Pegs.validateDesc(params, "PPPXPPPPP"),
        )
        assertEquals(
            "Too few pegs in game description",
            Pegs.validateDesc(params, "HOOOOOOOO"),
        )
        assertEquals(
            "Too few holes in game description",
            Pegs.validateDesc(params, "PPPPPPPPP"),
        )
        assertNull(Pegs.validateDesc(params, "PPHOOOOOO"))

        // A completed board cannot be described: it needs two pegs.
        assertEquals(
            "Too few pegs in game description",
            Pegs.validateDesc(Pegs.decodeParams("5x7cross"), "P" + "O".repeat(34)),
        )
    }

    @Test
    fun `a state owns its grid and compares by value`() {
        val desc = "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO"
        val state = Pegs.newState(Pegs.decodeParams("5x7cross"), desc) as Pegs.State
        assertEquals(35, state.grid.size)
        assertEquals(Pegs.GRID_HOLE, state.value(2, 3))
        assertEquals(Pegs.GRID_OBST, state.value(0, 0))
        assertEquals(Pegs.GRID_PEG, state.value(2, 1))
        assertFalse(state.completed)

        val copy = state.copy() as Pegs.State
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())
        assertNotSame(state.grid, copy.grid)
        copy.grid[0] = Pegs.GRID_PEG.toByte()
        assertNotEquals(state.value(0, 0), copy.value(0, 0), "a copy has its own grid")
    }

    @Test
    fun `the text format uses the C characters and a trailing newline`() {
        val fresh = Pegs.newState(
            Pegs.decodeParams("5x7cross"),
            "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO",
        )
        assertEquals(
            " *** \n" +
                " *** \n" +
                "*****\n" +
                "**-**\n" +
                "*****\n" +
                " *** \n" +
                " *** \n",
            Pegs.textFormat(fresh),
        )
        assertEquals("", Pegs.statusText(fresh, Pegs.newUi(fresh)))
        assertEquals(0, Pegs.status(fresh))
        assertFalse(Pegs.wantsStatusBar)
        assertFalse(Pegs.canSolve)
        assertNull(Pegs.solve(fresh))
        assertEquals(33, Pegs.preferredTileSize)
        assertEquals("Pegs", Pegs.name)
        assertEquals("pegs", Pegs.backendName)
    }

    @Test
    fun `execute_move parses the sscanf forms and applies one jump`() {
        val desc = "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO"
        val params = Pegs.decodeParams("5x7cross")
        val state = Pegs.newState(params, desc)

        val moved = Pegs.executeMove(state, "2,1-2,3") as Pegs.State
        assertEquals(Pegs.GRID_HOLE, moved.value(2, 1))
        assertEquals(Pegs.GRID_HOLE, moved.value(2, 2))
        assertEquals(Pegs.GRID_PEG, moved.value(2, 3))
        assertEquals(25, countPegs(moved))
        assertFalse(moved.completed)

        // The loose sscanf forms load, with the same result.
        for (text in listOf(
            "2,1-2,3junk",
            "2,1-2,3x",
            "2,1-2,3.5",
            "2,1-2,3 ",
            " 2,1-2,3",
            "+2,+1-+2,+3",
            "02,01-02,03",
        )) {
            assertEquals(moved, Pegs.executeMove(state, text), "parse '$text'")
        }

        // Rejected: a bad length, a bad endpoint, and a non-canonical
        // separator.
        for (text in listOf(
            "2,1 -2,3",
            "2,1-2,2",
            "2,1-2,4",
            "2,1-3,2",
            "2,1-4,3",
            "-1,1-1,3",
            "2,1-2,6",
            "2,1",
            "",
            "garbage",
        )) {
            assertNull(Pegs.executeMove(state, text), "reject '$text'")
        }

        // The grid constraints are checked, not only the geometry.
        assertNull(Pegs.executeMove(state, "2,3-2,1"), "destination is not a hole")
        assertNull(Pegs.executeMove(state, "2,2-2,0"), "the source is not a peg")
    }

    @Test
    fun `any single peg completes the game`() {
        // Two pegs at the left end of a 5x7 board and one hole after
        // them. One move leaves the peg at (2,0).
        val desc = "PPH" + "O".repeat(32)
        val params = Pegs.decodeParams("5x7cross")
        assertEquals(null, Pegs.validateDesc(params, desc))
        val state = Pegs.newState(params, desc)
        val moved = Pegs.executeMove(state, "0,0-2,0") as Pegs.State
        assertTrue(moved.completed)
        assertEquals(1, countPegs(moved))
        assertEquals(1, Pegs.status(moved))
        assertEquals("", Pegs.statusText(moved, Pegs.newUi(moved)))

        // The flag is sticky: a move that leaves two pegs keeps it.
        val grid = ByteArray(35)
        grid[0] = Pegs.GRID_PEG.toByte()
        grid[1] = Pegs.GRID_PEG.toByte()
        grid[2] = Pegs.GRID_HOLE.toByte()
        grid[4] = Pegs.GRID_PEG.toByte()
        val sticky = Pegs.State(5, 7, grid, completed = true)
        val after = Pegs.executeMove(sticky, "0,0-2,0") as Pegs.State
        assertEquals(2, countPegs(after))
        assertTrue(after.completed, "the completion flag is sticky")
        assertEquals(1, Pegs.status(after))
    }

    @Test
    fun `the cursor starts on the first peg or hole in column-major order`() {
        // The 5x7 cross has its first non-obstacle at (0,2): the scan is
        // x outer and y inner, not row-major.
        val state = Pegs.newState(
            Pegs.decodeParams("5x7cross"),
            "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO",
        )
        val ui = Pegs.newUi(state) as Pegs.Ui
        assertEquals(0, ui.curX)
        assertEquals(2, ui.curY)
        assertFalse(ui.curVisible)
        assertFalse(ui.dragging)
        assertFalse(ui.curJumping)
        assertNull(Pegs.cursorLocation(ui, state, 33), "a hidden cursor has no rectangle")
        Pegs.setCursorVisible(ui, true)
        assertEquals(
            Game.CursorRect(16, 82, 33, 33),
            Pegs.cursorLocation(ui, state, 33),
        )
    }

    @Test
    fun `the key label distinguishes select, cancel, and the rest`() {
        val state = Pegs.newState(
            Pegs.decodeParams("5x7cross"),
            "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO",
        )
        val ui = Pegs.newUi(state) as Pegs.Ui
        assertEquals("", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        Pegs.setCursorVisible(ui, true)
        assertEquals("Select", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Select", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        assertEquals("", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT or Buttons.MOD_SHFT))
        assertEquals("", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_UP))
        ui.curJumping = true
        assertEquals("Cancel", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        // The cursor on a hole has no label.
        ui.curJumping = false
        ui.curX = 2
        ui.curY = 3
        assertEquals("", Pegs.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
    }

    @Test
    fun `the nine golden descriptions still match`() {
        val expected = mapOf(
            "5x5random" to "5x5:OPPOOPPPPOPPHPPOPPPPOOPPO",
            "5x7cross" to "5x7:OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO",
            "5x9cross" to "5x9:OPPPOOPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPOOPPPO",
            "7x7cross" to "7x7:OOPPPOOOOPPPOOPPPPPPPPPPHPPPPPPPPPPOOPPPOOOOPPPOO",
            "7x7octagon" to "7x7:OOPPPOOOPPHPPOPPPPPPPPPPPPPPPPPPPPPOPPPPPOOOPPPOO",
            "7x7random" to "7x7:OOOOOOPOPOOPOPOPOOPPHPPPPHPPPPPPPPOOPPPHPPOOOOPOO",
            "7x9cross" to
                "7x9:OOPPPOOOOPPPOOOOPPPOOPPPPPPPPPPHPPPPPPPPPPOOPPPOOOOPPPOOOOPPPOO",
            "9x9cross" to "9x9:OOOPPPOOOOOOPPPOOOOOOPPPOOOPPPPPPPPPPPPPHPPPPPPPPPPPPPOOOPPPOOOOOOPPPOOOOOOPPPOOO",
            "9x9random" to
                "9x9:OOOOOOPOOOOOOPPPPPOOOOPPHPPOOPOPPPPPOOPPPPHPPPPHPPPPPPOOPPPPHPPOOOOPPPOOOOOPPHPOO",
        )
        for ((params, id) in expected) {
            assertEquals(id, Midend.newGame(Pegs, params, "12345").id(), "seed 12345 '$params'")
        }
        assertEquals(
            expected["7x7cross"],
            Midend.newGame(Pegs, "", "12345").id(),
            "the empty parameter string uses the default",
        )
    }

    @Test
    fun `cross ignores the random stream and random consumes it`() {
        val cross = Pegs.decodeParams("7x7cross") as Pegs.Params
        val first = PegsGeneration.generate(cross.width, cross.height, cross.type, RandomState.fromSeed("1"))
        val second = PegsGeneration.generate(cross.width, cross.height, cross.type, RandomState.fromSeed("2"))
        assertEquals(first.desc, second.desc, "Cross uses no RNG")

        val random = Pegs.decodeParams("7x7random") as Pegs.Params
        val a = PegsGeneration.generate(random.width, random.height, random.type, RandomState.fromSeed("1"))
        val b = PegsGeneration.generate(random.width, random.height, random.type, RandomState.fromSeed("2"))
        assertTrue(a.desc != b.desc, "Random uses the RNG")
    }

    @Test
    fun `the random generator retries in the same stream`() {
        // The first attempt for this seed fails the extent test. The
        // pinned description is the second attempt, so an extra draw, a
        // reseed, or a changed retry rule produces a different board.
        val params = Pegs.decodeParams("7x7random") as Pegs.Params
        val generated = PegsGeneration.generate(
            params.width,
            params.height,
            params.type,
            RandomState.fromSeed("1"),
        )
        assertEquals(2, generated.attempts)
        assertEquals(
            "OOPOOOOPPPPPOOHPPPOOOPPPPPPOPPHPHPPOOPPPPOOOPPOPO",
            generated.desc,
        )
        // A second seed needs more attempts, so the retry count is real
        // and not hard-coded at two.
        assertEquals(
            7,
            PegsGeneration.generate(
                params.width,
                params.height,
                params.type,
                RandomState.fromSeed("0"),
            ).attempts,
        )
    }

    @Test
    fun `the octagon draws the hole from the three classes`() {
        val params = Pegs.decodeParams("7x7octagon") as Pegs.Params
        val holes = (1..31).associateWith { seed ->
            val desc = PegsGeneration.generate(
                params.width,
                params.height,
                params.type,
                RandomState.fromSeed(seed.toString()),
            ).desc
            val index = desc.indexOf('H')
            (index % 7) to (index / 7)
        }
        // Class 0: a corner. Class 1: two from the centre. Class 2: one
        // from the centre. All three appear, so the selector and the
        // branch order are exercised.
        val class0 = setOf(
            4 to 0, 2 to 0, 4 to 6, 2 to 6, 0 to 2, 6 to 2, 0 to 4, 6 to 4,
        )
        val class1 = setOf(5 to 3, 1 to 3, 3 to 5, 3 to 1)
        val class2 = setOf(4 to 3, 2 to 3, 3 to 4, 3 to 2)
        assertTrue(holes.values.any { it in class0 }, "a corner hole")
        assertTrue(holes.values.any { it in class1 }, "a two-from-centre hole")
        assertTrue(holes.values.any { it in class2 }, "a one-from-centre hole")
        assertEquals(4 to 0, holes[1])
        assertEquals(5 to 3, holes[11])
        assertEquals(4 to 3, holes[2])
        assertEquals(3 to 1, hole(12345))
    }

    @Test
    fun `a three-cell drag to a hole is cancelled by the length rule`() {
        // On the 5x7 cross, (2,0) and the cell two cells away (2,2) are
        // pegs, and the cell three cells away (2,3) is the centre hole.
        // Every content check would pass, so only the drag length can
        // cancel this release.
        val board = Pegs.newState(
            Pegs.decodeParams("5x7cross"),
            "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO",
        )
        val ui = Pegs.newUi(board) as Pegs.Ui
        ui.dragging = true
        ui.sx = 2
        ui.sy = 0
        val release = Pegs.interpretMove(
            board,
            ui,
            cellCentre(2),
            cellCentre(3),
            Buttons.LEFT_RELEASE,
            TILE,
        )
        assertEquals(MoveResult.UiUpdate, release, "a three-cell release is not a move")
        assertFalse(ui.dragging)

        // Through the midend the release is a UI update, and the save
        // and the history do not change.
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val before = midend.serialise().serialise()
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(cellCentre(2), cellCentre(0), Buttons.LEFT_BUTTON, TILE),
        )
        assertTrue((midend.ui as Pegs.Ui).dragging)
        midend.processKey(cellCentre(2), cellCentre(3), Buttons.LEFT_DRAG, TILE)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(cellCentre(2), cellCentre(3), Buttons.LEFT_RELEASE, TILE),
            "a cancelled drag is a UI update",
        )
        assertFalse((midend.ui as Pegs.Ui).dragging)
        assertEquals(before, midend.serialise().serialise(), "the drag changes no save")
        assertEquals(emptyList<String>(), midend.serialise().values("MOVE"))
        assertEquals(1, midend.stateCount)
    }

    @Test
    fun `undo cancels a drag without changing the save or the history`() {
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        // One real move, so undo has somewhere to go.
        midend.processKey(cellCentre(2), cellCentre(1), Buttons.LEFT_BUTTON, TILE)
        midend.processKey(cellCentre(2), cellCentre(3), Buttons.LEFT_DRAG, TILE)
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(cellCentre(2), cellCentre(3), Buttons.LEFT_RELEASE, TILE),
        )
        assertEquals(listOf("2,1-2,3"), midend.serialise().values("MOVE"))
        val afterMove = midend.serialise().serialise()

        // A press starts a drag and adds no record. Undo must cancel the
        // drag, because the drag source can stop being a peg.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(cellCentre(2), cellCentre(0), Buttons.LEFT_BUTTON, TILE),
        )
        assertTrue((midend.ui as Pegs.Ui).dragging, "the press starts a drag")
        assertEquals(afterMove, midend.serialise().serialise())

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'u'.code, TILE))
        assertFalse((midend.ui as Pegs.Ui).dragging, "undo cancels the drag")
        // Undo moves the position only: the move text, the history
        // length, and the redo branch are unchanged.
        assertEquals(listOf("2,1-2,3"), midend.serialise().values("MOVE"))
        assertEquals(2, midend.stateCount)
        assertEquals("1", midend.serialise().value("STATEPOS"))
        assertTrue(midend.canRedo)
    }

    @Test
    fun `undo cancels a pending keyboard jump`() {
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        midend.processKey(cellCentre(2), cellCentre(1), Buttons.LEFT_BUTTON, TILE)
        midend.processKey(cellCentre(2), cellCentre(3), Buttons.LEFT_DRAG, TILE)
        midend.processKey(cellCentre(2), cellCentre(3), Buttons.LEFT_RELEASE, TILE)
        val ui = midend.ui as Pegs.Ui

        // Select twice: the first shows the cursor, the second arms the
        // jump. The cursor starts on the peg at (0,2) and the move does
        // not touch it.
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE))
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.CURSOR_SELECT, TILE))
        assertTrue(ui.curJumping, "select arms a jump")

        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, 'u'.code, TILE))
        assertFalse(ui.curJumping, "undo cancels the pending jump")
        assertEquals(listOf("2,1-2,3"), midend.serialise().values("MOVE"))
        assertEquals(2, midend.stateCount)
        assertEquals("1", midend.serialise().value("STATEPOS"))
    }

    /** The view coordinate of a cell centre, as the shell sends it. */
    private fun cellCentre(cell: Int): Int = TILE / 2 + cell * TILE + TILE / 2

    private fun hole(seed: Int): Pair<Int, Int> {
        val params = Pegs.decodeParams("7x7octagon") as Pegs.Params
        val desc = PegsGeneration.generate(
            params.width,
            params.height,
            params.type,
            RandomState.fromSeed(seed.toString()),
        ).desc
        val index = desc.indexOf('H')
        return (index % 7) to (index / 7)
    }

    private fun countPegs(state: Pegs.State): Int =
        state.grid.count { it.toInt() == Pegs.GRID_PEG }

    private companion object {
        const val SEED = "12345"
        const val TILE = 33
    }
}
