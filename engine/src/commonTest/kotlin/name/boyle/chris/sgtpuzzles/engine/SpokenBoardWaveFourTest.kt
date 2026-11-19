package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Games: the fifteen wave 4 games of D-01.
 *
 * Each board comes from a frozen seed-12345 game ID, so no generation
 * runs. The test pins the unit model of every shape that is not a plain
 * cell: the edge of `loopy` and `palisade`, the point of `untangle`, the
 * palette of `guess`, the laser ring of `blackbox`, and the moving bands
 * of `netslide`. It also pins the core promise of the design: a unit
 * action is the press and release a finger makes at the unit centre and
 * produces the move the game documents. A move that needs a drag, an
 * edge midpoint, or a second unit has no action, and the test says so.
 */
class SpokenBoardWaveFourTest {

    private fun board(game: Game, id: String): SpokenBoard {
        val midend = Midend.fromGameId(game, id)
        return game.spokenBoard(midend.state, midend.ui, TILE)
    }

    private fun SpokenBoard.cell(x: Int, y: Int): SpokenUnit = units[y * columns + x]

    private fun unitWith(board: SpokenBoard, prefix: String): SpokenUnit =
        assertNotNull(
            board.units.firstOrNull { it.text.startsWith(prefix) },
            "no unit starts with '$prefix': ${board.units.take(5).map { it.text }}",
        )

    /**
     * The move of the button pair a finger makes at the centre of [unit]:
     * the press, then the release, then whichever of the two reported a
     * move.
     */
    private fun click(
        game: Game,
        state: Game.State,
        ui: Game.Ui,
        unit: SpokenUnit,
        button: Int = Buttons.LEFT_BUTTON,
    ): MoveResult {
        val x = unit.left + unit.width / 2
        val y = unit.top + unit.height / 2
        val press = game.interpretMove(state, ui, x, y, button, TILE)
        val release = game.interpretMove(
            state,
            ui,
            x,
            y,
            button + (Buttons.LEFT_RELEASE - Buttons.LEFT_BUTTON),
            TILE,
        )
        return if (release is MoveResult.Move) release else press
    }

    /** The state after the click, or the failure message. */
    private fun move(
        game: Game,
        midend: Midend,
        unit: SpokenUnit,
        button: Int = Buttons.LEFT_BUTTON,
    ): Game.State {
        val result = click(game, midend.state, midend.ui, unit, button)
        val move = (result as? MoveResult.Move)?.text
        assertNotNull(move, "the click on '${unit.text}' made no move: $result")
        return assertNotNull(
            game.executeMove(midend.state, move),
            "the game refused its own move '$move'",
        )
    }

    // ------------------------------------------------------------------
    // Loopy: one unit per edge, and one per clue face
    // ------------------------------------------------------------------

    @Test
    fun `loopy speaks every edge of a square grid by its two ends`() {
        val midend = Midend.fromGameId(Loopy, LOOPY_SQUARE)
        val board = Loopy.spokenBoard(midend.state, midend.ui, TILE)
        val state = midend.state as Loopy.State

        assertEquals(0, board.rows)
        assertEquals(0, board.columns)
        assertEquals(state.numEdges + state.clues.count { it >= 0 }, board.units.size)

        // The first face: a plain edge with two ends in row and column.
        val first = board.units[0]
        assertEquals("edge from row 1, column 1 to row 1, column 2, no line", first.text)
        assertEquals(
            listOf(
                SpokenAction("Line", Buttons.LEFT_BUTTON),
                SpokenAction("Cross", Buttons.RIGHT_BUTTON),
            ),
            first.actions,
        )

        // The clue face carries the number the drawing puts on it.
        assertTrue(
            board.units.any { it.text.startsWith("clue ") && it.actions.isEmpty() },
            "no clue unit: ${board.units.takeLast(3).map { it.text }}",
        )
    }

    @Test
    fun `loopy turns an edge with the pair a finger makes`() {
        val midend = Midend.fromGameId(Loopy, LOOPY_SQUARE)
        val before = Loopy.spokenBoard(midend.state, midend.ui, TILE)
        val lined = move(Loopy, midend, before.units[0])
        val after = Loopy.spokenBoard(lined, midend.ui, TILE)
        assertEquals(
            "edge from row 1, column 1 to row 1, column 2, line",
            after.units[0].text,
        )
        assertEquals(
            listOf(
                SpokenAction("Cross", Buttons.LEFT_BUTTON),
                SpokenAction("Clear", Buttons.RIGHT_BUTTON),
            ),
            after.units[0].actions,
        )

        // The right button crosses it out from the unset state.
        val fresh = Midend.fromGameId(Loopy, LOOPY_SQUARE)
        val unit = Loopy.spokenBoard(fresh.state, fresh.ui, TILE).units[0]
        val crossed = move(Loopy, fresh, unit, Buttons.RIGHT_BUTTON)
        val crossBoard = Loopy.spokenBoard(crossed, fresh.ui, TILE)
        assertEquals(
            "edge from row 1, column 1 to row 1, column 2, crossed out",
            crossBoard.units[0].text,
        )
        // From there the right button goes on to a line and the left
        // one clears the mark, the rest of the three-state cycle.
        assertEquals(
            listOf(
                SpokenAction("Clear", Buttons.LEFT_BUTTON),
                SpokenAction("Line", Buttons.RIGHT_BUTTON),
            ),
            crossBoard.units[0].actions,
        )
    }

    @Test
    fun `loopy names the other grid types by the cells an edge joins`() {
        val board = board(Loopy, LOOPY_DODECAGONAL)
        assertTrue(board.units.isNotEmpty())
        val edge = board.units.first { it.text.startsWith("edge ") }
        assertTrue(
            edge.text.startsWith("edge between cell ") || edge.text.startsWith("edge on cell "),
            "an irregular grid names an edge by rows: '${edge.text}'",
        )
        assertTrue(
            board.units.any { it.text.startsWith("clue ") && it.text.contains(" at cell ") },
            "an irregular grid names a clue by a row: ${board.units.last().text}",
        )
    }

    @Test
    fun `loopy speaks the cursor edge and keeps its count inside the cap`() {
        val midend = Midend.fromGameId(Loopy, LOOPY_SQUARE)
        val ui = midend.ui as Loopy.Ui
        ui.curVisible = true
        val board = Loopy.spokenBoard(midend.state, ui, TILE)
        assertTrue(
            board.units.count { it.text.endsWith(", cursor") } == 1,
            "the edge cursor is spoken ${board.units.count { it.text.endsWith(", cursor") }} times",
        )
    }

    // ------------------------------------------------------------------
    // Palisade: one unit per interior border
    // ------------------------------------------------------------------

    @Test
    fun `palisade speaks a cell clue and an interior border`() {
        val board = board(Palisade, PALISADE)
        assertEquals(5, board.rows)
        assertEquals(5, board.columns)
        // 25 cells and the 40 interior borders of a 5x5 grid.
        assertEquals(25 + 40, board.units.size)
        assertEquals("empty", board.cell(0, 0).text)
        assertTrue(board.cell(0, 0).actions.isEmpty())
        assertEquals("clue 3", board.cell(1, 0).text)

        val border = unitWith(
            board,
            "Border between row 1 and row 2, column 1",
        )
        assertEquals("Border between row 1 and row 2, column 1, empty", border.text)
        assertEquals(
            listOf(
                SpokenAction("Line", Buttons.LEFT_BUTTON),
                SpokenAction("Cross", Buttons.RIGHT_BUTTON),
            ),
            border.actions,
        )
    }

    @Test
    fun `palisade draws a border with the pair a finger makes`() {
        val midend = Midend.fromGameId(Palisade, PALISADE)
        val border = unitWith(
            Palisade.spokenBoard(midend.state, midend.ui, TILE),
            "Border between row 1 and row 2, column 1",
        )
        val lined = move(Palisade, midend, border)
        val after = Palisade.spokenBoard(lined, midend.ui, TILE)
        val unit = unitWith(after, "Border between row 1 and row 2, column 1")
        assertEquals("Border between row 1 and row 2, column 1, line", unit.text)
        assertEquals(
            listOf(SpokenAction("Clear", Buttons.LEFT_BUTTON)),
            unit.actions,
        )

        // The right button crosses a border out and clears it again.
        val fresh = Midend.fromGameId(Palisade, PALISADE)
        val crossed = move(
            Palisade,
            fresh,
            unitWith(
                Palisade.spokenBoard(fresh.state, fresh.ui, TILE),
                "Border between row 1 and row 2, column 2",
            ),
            Buttons.RIGHT_BUTTON,
        )
        val crossUnit = unitWith(
            Palisade.spokenBoard(crossed, fresh.ui, TILE),
            "Border between row 1 and row 2, column 2",
        )
        assertEquals("Border between row 1 and row 2, column 2, cross", crossUnit.text)
        assertEquals(
            listOf(SpokenAction("Clear", Buttons.RIGHT_BUTTON)),
            crossUnit.actions,
        )
    }

    // ------------------------------------------------------------------
    // Slant and Pattern: a cell holds a value and two buttons turn it
    // ------------------------------------------------------------------

    @Test
    fun `slant turns a slash with the two pointer buttons`() {
        val midend = Midend.fromGameId(Slant, SLANT)
        val board = Slant.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(5, board.rows)
        assertEquals(5, board.columns)
        assertEquals(
            listOf(
                SpokenAction("Clockwise", Buttons.LEFT_BUTTON),
                SpokenAction("Anticlockwise", Buttons.RIGHT_BUTTON),
            ),
            board.cell(0, 0).actions,
        )
        assertTrue(
            board.cell(0, 0).text.startsWith("blank"),
            "the first square is '${board.cell(0, 0).text}'",
        )

        val turned = move(Slant, midend, board.cell(0, 0))
        val after = Slant.spokenBoard(turned, midend.ui, TILE)
        assertTrue(
            after.cell(0, 0).text.startsWith("backslash") ||
                after.cell(0, 0).text.startsWith("slash"),
            "the clockwise turn left '${after.cell(0, 0).text}'",
        )
    }

    @Test
    fun `slant follows the left-button preference in its action words`() {
        val midend = Midend.fromGameId(Slant, SLANT)
        val ui = midend.ui as Slant.Ui
        val before = Slant.spokenBoard(midend.state, ui, TILE)
        ui.swapButtons = true
        val swapped = Slant.spokenBoard(midend.state, ui, TILE)
        assertEquals(
            listOf(
                SpokenAction("Anticlockwise", Buttons.LEFT_BUTTON),
                SpokenAction("Clockwise", Buttons.RIGHT_BUTTON),
            ),
            swapped.cell(0, 0).actions,
        )
        assertTrue(before.cell(0, 0).actions != swapped.cell(0, 0).actions)
    }

    @Test
    fun `pattern names the three states and cycles them`() {
        val midend = Midend.fromGameId(Pattern, PATTERN)
        val board = Pattern.spokenBoard(midend.state, midend.ui, TILE)
        val cell = board.cell(0, 0)
        assertEquals("Grey", cell.text)
        assertEquals(
            listOf(
                SpokenAction("Black", Buttons.LEFT_BUTTON),
                SpokenAction("White", Buttons.RIGHT_BUTTON),
            ),
            cell.actions,
        )

        val filled = move(Pattern, midend, cell)
        assertEquals("Black", Pattern.spokenBoard(filled, midend.ui, TILE).cell(0, 0).text)
    }

    // ------------------------------------------------------------------
    // Tracks: a square is the unit, an edge stays on the cursor
    // ------------------------------------------------------------------

    @Test
    fun `tracks flips a square and keeps an edge on the keys`() {
        val midend = Midend.fromGameId(Tracks, TRACKS)
        val board = Tracks.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(8, board.rows)
        assertEquals(8, board.columns)
        // 64 cells and one count per row and per column.
        assertEquals(64 + 16, board.units.size)

        val index = board.units.indexOfFirst { it.actions.isNotEmpty() }
        assertTrue(index >= 0, "no square offers the square flip")
        val cell = board.units[index]
        assertEquals(
            listOf(
                SpokenAction("Track", Buttons.LEFT_BUTTON),
                SpokenAction("No track", Buttons.RIGHT_BUTTON),
            ),
            cell.actions,
        )

        val flipped = move(Tracks, midend, cell)
        val after = Tracks.spokenBoard(flipped, midend.ui, TILE)
        assertTrue(
            after.units[index].text.contains("track square"),
            "the square flip left '${after.units[index].text}'",
        )

        // No unit can reach a single edge: that press is not the centre.
        assertTrue(
            board.units.none { it.text.contains("edge") },
            "a unit claims a single track edge",
        )
    }

    // ------------------------------------------------------------------
    // Bridges and Untangle: the drag stays on the cursor
    // ------------------------------------------------------------------

    @Test
    fun `bridges marks an island and leaves the bridge drag on the keys`() {
        val midend = Midend.fromGameId(Bridges, BRIDGES)
        val board = Bridges.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(7, board.rows)
        assertEquals(7, board.columns)

        val island = unitWith(board, "island ")
        assertEquals(
            listOf(SpokenAction("Mark", Buttons.LEFT_BUTTON)),
            island.actions,
        )

        val marked = move(Bridges, midend, island)
        val after = Bridges.spokenBoard(marked, midend.ui, TILE)
        val unit = unitWith(after, island.text.removeSuffix(", marked"))
        assertTrue(unit.text.endsWith(", marked"), "'${unit.text}' is not marked")
        assertEquals(
            listOf(SpokenAction("Unmark", Buttons.LEFT_BUTTON)),
            unit.actions,
        )
    }

    @Test
    fun `untangle speaks a point and offers no action`() {
        val board = board(Untangle, UNTANGLE)
        assertEquals(0, board.rows)
        assertEquals(0, board.columns)
        assertEquals(6, board.units.size)
        assertEquals("point 1", board.units[0].text.substringBefore(", joined"))
        assertTrue(
            board.units.none { it.actions.isNotEmpty() },
            "a point offers an action, but a press only starts a drag",
        )
        // The point rectangle is a touch target on the point itself.
        assertTrue(board.units.all { it.width >= 13 && it.height >= 13 })
    }

    // ------------------------------------------------------------------
    // Guess: the palette, the draft, and the one real button
    // ------------------------------------------------------------------

    @Test
    fun `guess names the palette in colour names and offers Submit`() {
        val midend = Midend.fromGameId(Guess, GUESS)
        val board = Guess.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(0, board.rows)
        assertEquals(0, board.columns)
        // Six colours, ten rows of four pegs, ten feedbacks, no solution.
        assertEquals(6 + 10 * 4 + 10, board.units.size)
        assertEquals(
            listOf("red", "yellow", "green", "blue", "orange", "purple"),
            board.units.take(6).map { it.text },
        )
        assertTrue(
            board.units.take(6).all { it.actions.isEmpty() },
            "a palette swatch claims an action, but a press only starts a drag",
        )

        // A full draft makes the row submit, and the press and release
        // at the feedback unit centre are that submit.
        val ui = midend.ui as Guess.Ui
        ui.currPegs[0] = 1
        ui.currPegs[1] = 2
        ui.currPegs[2] = 1
        ui.currPegs[3] = 2
        ui.markable = Guess.isMarkable(ui.params, ui.currPegs)
        val ready = Guess.spokenBoard(midend.state, ui, TILE)
        val feedback = unitWith(ready, "current row")
        assertEquals(
            listOf(SpokenAction("Submit", Buttons.LEFT_BUTTON)),
            feedback.actions,
        )

        val played = move(Guess, midend, feedback)
        val after = Guess.spokenBoard(played, midend.ui, TILE)
        assertTrue(
            after.units.any { it.text.endsWith("exact, 1 wrong place") },
            "the submitted row has no feedback: ${after.units.map { it.text }}",
        )
    }

    @Test
    fun `guess holds a peg with the right pointer button`() {
        val midend = Midend.fromGameId(Guess, GUESS)
        val board = Guess.spokenBoard(midend.state, midend.ui, TILE)
        val peg = board.units[6]
        assertEquals("empty", peg.text)
        assertEquals(
            listOf(SpokenAction("Hold", Buttons.RIGHT_BUTTON)),
            peg.actions,
        )
        click(Guess, midend.state, midend.ui, peg, Buttons.RIGHT_BUTTON)
        assertTrue((midend.ui as Guess.Ui).holds[0], "the hold did not move to the peg")
        val after = Guess.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals("empty, held", after.units[6].text)
        assertEquals(
            listOf(SpokenAction("Unhold", Buttons.RIGHT_BUTTON)),
            after.units[6].actions,
        )
    }

    // ------------------------------------------------------------------
    // Map and Galaxies: colour is read, the drag stays on the cursor
    // ------------------------------------------------------------------

    @Test
    fun `map names its region and keeps the colour drop on the keys`() {
        val board = board(Map, MAP)
        assertEquals(15, board.rows)
        assertEquals(20, board.columns)
        assertEquals(300, board.units.size)
        assertTrue(
            board.cell(0, 0).text.startsWith("region 0, C, given"),
            "the first map cell is '${board.cell(0, 0).text}'",
        )
        assertTrue(
            board.units.all { it.actions.isEmpty() },
            "a map region claims an action, but a press only starts a drag",
        )
    }

    @Test
    fun `galaxies names a dot, a colour, and a wall with no action`() {
        val board = board(Galaxies, GALAXIES)
        assertEquals(7, board.rows)
        assertEquals(7, board.columns)
        assertEquals(49, board.units.size)
        assertTrue(
            board.units.any { it.text.contains("dot") || it.text.contains("wall") },
            "no unit names a dot or a wall: ${board.units.take(3).map { it.text }}",
        )
        assertTrue(
            board.units.all { it.actions.isEmpty() },
            "a galaxy cell claims an action, but a click only sets a grid edge",
        )
    }

    // ------------------------------------------------------------------
    // Black Box: the arena, the ring, and the reveal
    // ------------------------------------------------------------------

    @Test
    fun `blackbox speaks the arena, the ring, and the reveal button`() {
        val midend = Midend.fromGameId(BlackBox, BLACKBOX)
        val board = BlackBox.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(5, board.rows)
        assertEquals(5, board.columns)
        // 25 cells, the reveal button, and the 20 laser sites.
        assertEquals(25 + 1 + 20, board.units.size)
        assertEquals(
            listOf(
                SpokenAction("Ball", Buttons.LEFT_BUTTON),
                SpokenAction("Lock", Buttons.RIGHT_BUTTON),
            ),
            board.cell(0, 0).actions,
        )
        assertEquals("Reveal", board.units[25].text)
        // The reveal waits for the three balls of the smallest board.
        assertTrue(
            board.units[25].actions.isEmpty(),
            "the reveal is offered before any ball is placed",
        )
        val three = Midend.fromGameId(BlackBox, BLACKBOX)
        var guessed = three.state
        for (cell in listOf("T1,1", "T1,2", "T1,3")) {
            guessed = assertNotNull(BlackBox.executeMove(guessed, cell), "the ball '$cell' failed")
        }
        assertEquals(
            listOf(SpokenAction("Reveal", Buttons.LEFT_BUTTON)),
            BlackBox.spokenBoard(guessed, three.ui, TILE).units[25].actions,
        )
        assertEquals("Top edge, column 1, not fired", board.units[26].text)
        assertEquals(
            listOf(
                SpokenAction("Fire", Buttons.LEFT_BUTTON),
                SpokenAction("Lock", Buttons.RIGHT_BUTTON),
            ),
            board.units[26].actions,
        )
    }

    @Test
    fun `blackbox fires a laser with the pair a finger makes`() {
        val midend = Midend.fromGameId(BlackBox, BLACKBOX)
        val board = BlackBox.spokenBoard(midend.state, midend.ui, TILE)
        val fired = move(BlackBox, midend, board.units[26])
        val after = BlackBox.spokenBoard(fired, midend.ui, TILE)
        val site = after.units[26]
        assertTrue(
            site.text.startsWith("Top edge, column 1, ") && site.text != "Top edge, column 1, not fired",
            "the fired site still says '${site.text}'",
        )
        assertTrue(
            site.actions.none { it.label == "Fire" },
            "a fired site still offers Fire",
        )
    }

    @Test
    fun `blackbox locks a cell and clears its ball button`() {
        val midend = Midend.fromGameId(BlackBox, BLACKBOX)
        val board = BlackBox.spokenBoard(midend.state, midend.ui, TILE)
        val locked = move(BlackBox, midend, board.cell(0, 0), Buttons.RIGHT_BUTTON)
        val after = BlackBox.spokenBoard(locked, midend.ui, TILE)
        assertEquals("empty, locked", after.cell(0, 0).text)
        assertEquals(
            listOf(SpokenAction("Unlock", Buttons.RIGHT_BUTTON)),
            after.cell(0, 0).actions,
        )
    }

    // ------------------------------------------------------------------
    // Net and Netslide: a tile turns, a band slides
    // ------------------------------------------------------------------

    @Test
    fun `net turns a tile and locks it`() {
        val midend = Midend.fromGameId(Net, NET)
        val board = Net.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(5, board.rows)
        assertEquals(5, board.columns)
        assertEquals(25, board.units.size)

        val turned = move(Net, midend, board.cell(0, 0))
        val after = Net.spokenBoard(turned, midend.ui, TILE)
        assertTrue(
            after.cell(0, 0).text != board.cell(0, 0).text,
            "the turn changed nothing: '${after.cell(0, 0).text}'",
        )

        val locked = move(Net, midend, board.cell(1, 1), Buttons.RIGHT_BUTTON)
        val lockedBoard = Net.spokenBoard(locked, midend.ui, TILE)
        assertTrue(
            lockedBoard.cell(1, 1).text.endsWith(", locked"),
            "'${lockedBoard.cell(1, 1).text}' is not locked",
        )
        assertEquals(
            listOf(SpokenAction("Unlock", Buttons.RIGHT_BUTTON)),
            lockedBoard.cell(1, 1).actions,
        )
    }

    @Test
    fun `netslide slides a band and does not act on a cell`() {
        val midend = Midend.fromGameId(Netslide, NETSLIDE)
        val board = Netslide.spokenBoard(midend.state, midend.ui, TILE)
        assertEquals(3, board.rows)
        assertEquals(3, board.columns)
        // 9 cells and four bands for each of the three rows and columns.
        assertEquals(9 + 12, board.units.size)
        assertTrue(board.cell(0, 0).actions.isEmpty())

        val topBand = board.units[9]
        assertEquals("Column 1, top band", topBand.text)
        assertEquals(
            listOf(
                SpokenAction("Slide down", Buttons.LEFT_BUTTON),
                SpokenAction("Slide up", Buttons.RIGHT_BUTTON),
            ),
            topBand.actions,
        )

        val slid = move(Netslide, midend, topBand)
        val after = Netslide.spokenBoard(slid, midend.ui, TILE)
        assertEquals("Moves: 1 Active: 5/9", after.summary)
        assertTrue(after.cell(0, 0).text != board.cell(0, 0).text)
    }

    // ------------------------------------------------------------------
    // Inertia and Pearl: a direction click, and a centre that does nothing
    // ------------------------------------------------------------------

    @Test
    fun `inertia names the direction of a click and offers it`() {
        val board = board(Inertia, INERTIA)
        assertEquals(8, board.rows)
        assertEquals(10, board.columns)
        val player = board.units.first { it.text.contains("player") }
        assertTrue(player.text.endsWith(", cursor"), "'${player.text}' is not the cursor")
        assertTrue(player.actions.isEmpty(), "the player cell offers a move")

        // A click on any other cell is the nearest of the eight
        // directions from the player, and the label is that direction.
        val other = board.units.first { it !== player }
        assertEquals(1, other.actions.size)
        assertEquals(Buttons.LEFT_BUTTON, other.actions[0].button)
        assertTrue(
            other.actions[0].label in DIRECTIONS,
            "'${other.actions[0].label}' is not one of the eight directions",
        )

        val midend = Midend.fromGameId(Inertia, INERTIA)
        val fresh = Inertia.spokenBoard(midend.state, midend.ui, TILE)
            .units[board.units.indexOf(other)]
        val moved = move(Inertia, midend, fresh)
        val after = Inertia.spokenBoard(moved, midend.ui, TILE)
        assertTrue(
            after.units.first { it.text.contains("player") }.spokenLabel() !=
                player.spokenLabel(),
            "the player did not move",
        )
    }

    @Test
    fun `pearl has no action because a centre tap changes nothing`() {
        val board = board(Pearl, PEARL)
        assertEquals(6, board.rows)
        assertEquals(6, board.columns)
        assertEquals(36, board.units.size)
        assertTrue(
            board.units.any { it.text.contains("clue") },
            "no clue unit: ${board.units.map { it.text }}",
        )
        assertTrue(
            board.units.all { it.actions.isEmpty() },
            "a pearl cell offers an action, but a centre tap is inert",
        )
    }

    // ------------------------------------------------------------------
    // The shared helpers
    // ------------------------------------------------------------------

    @Test
    fun `the side list reads as one phrase`() {
        assertEquals("", sideSpeech(emptyList()))
        assertEquals("up", sideSpeech(listOf("up")))
        assertEquals("up and left", sideSpeech(listOf("up", "left")))
        assertEquals("up, left and down", sideSpeech(listOf("up", "left", "down")))
    }

    @Test
    fun `a board is capped by its unit count and keeps the cursor`() {
        val many = List(BOARD_UNIT_CAP) {
            SpokenUnit(0, 0, it, 0, 1, 1, "unit $it")
        }
        val atCap = SpokenBoard(0, 0, "", many).cappedUnits(many[0])
        assertSame(many, atCap.units)

        val above = List(BOARD_UNIT_CAP + 1) {
            SpokenUnit(0, 0, it, 0, 1, 1, "unit $it")
        }
        val cursor = above[17]
        val capped = SpokenBoard(0, 0, "summary", above).cappedUnits(cursor)
        assertEquals(1, capped.units.size)
        assertSame(cursor, capped.units[0])
        assertEquals("summary", capped.summary)
        assertTrue(SpokenBoard(0, 0, "", above).cappedUnits(null).units.isEmpty())
    }

    @Test
    fun `the unit under a point is the unit that holds it`() {
        val a = SpokenUnit(0, 0, 0, 0, 10, 10, "a")
        val b = SpokenUnit(0, 0, 10, 0, 10, 10, "b")
        val units = listOf(a, b)
        assertSame(a, units.unitAt(0, 0))
        assertSame(a, units.unitAt(9, 9))
        assertSame(b, units.unitAt(10, 0))
        assertEquals(null, units.unitAt(20, 0))
        assertEquals(null, units.unitAt(0, 10))
    }

    @Test
    fun `guess caps a long history to the cursor unit alone`() {
        val params = Guess.Params(6, 4, 500, allowBlank = false, allowMultiple = true)
        val state = Guess.newState(params, "ac769e34")
        val ui = Guess.newUi(state) as Guess.Ui
        ui.currPegs[2] = 3
        ui.pegCur = 2
        ui.displayCur = true
        val board = Guess.spokenBoard(state, ui, TILE)
        assertEquals(1, board.units.size)
        assertEquals("green, cursor", board.units[0].text)
    }

    private companion object {
        const val TILE = 32

        /** Frozen seed-12345 IDs, the smallest preset of each game. */
        const val LOOPY_SQUARE = "7x7t0:a2b2c02c22a32b20a21a032c3c22a21b2a3a1a"
        const val LOOPY_DODECAGONAL = "3x2t13:2c152a3a21a21c31c54c0b"
        const val SLANT = "5x5:a2d1b2a2b3d3b2b21a1c1110"
        const val PATTERN = "10x10:4/5/6/7/5/1.3/6/3.1/2.4/3/4/3/2/2.1/5/6.2/6.3/5.2/4.1/4"
        const val TRACKS = "8x8:i5c5CiCu9pA,4,4,4,3,4,7,5,S3,3,6,6,S5,4,4,3,3"
        const val BRIDGES = "7x7m2:3e2g6a3c2i1b3h4d4a"
        const val PALISADE = "5x5n5:a313d3d3f32a1"
        const val UNTANGLE = "6:0-1,0-2,0-3,0-5,1-3,1-4,1-5,2-3,2-4,2-5,3-4"
        const val GUESS = "c6p4g10Bm:ac769e34"
        const val MAP =
            "20x15n30:ddbehafbbafbabccabkaaacabaabccbadahacbcbbbjaaaaaabaaeeaaabdbjcabcaiaj" +
                "cfbdabacblabahcecabzbaiclbaboagbcacdedabaaeacebdcacagaaacaahfbgcaaadefbcabbacae" +
                "abbaabaaefbaaabaaffbcodgdjad,212a30a2g22d1a023b21"
        const val GALAXIES = "7x7:cjrbhizuisfvf"
        const val BLACKBOX = "w5h5m3M3:7f1b9a74d74b19f6"
        const val NET = "5x5:1863151ea86e7e3c7abc21a32"
        const val NETSLIDE = "3x3:21c4d5963"
        const val INERTIA =
            "10x8:bswmsgmSbmbmgmgbwgbbmgmwgwwswswmbmssbgmswbsbggbsswgwggbgsmswwswmwssbgbmmwgsbgmwm"
        const val PEARL = "6x6:BaWnBBdBaWfWc"

        /** The eight names `directionName` can give a click. */
        val DIRECTIONS = setOf(
            "up",
            "up and right",
            "right",
            "down and right",
            "down",
            "down and left",
            "left",
            "up and left",
        )
    }
}
