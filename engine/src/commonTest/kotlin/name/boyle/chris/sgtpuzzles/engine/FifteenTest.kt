package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Level: semantic. API edges of the first ported game and midend. */
class FifteenTest {

    @Test
    fun `parameters decode, encode, and validate`() {
        assertEquals(Fifteen.Params(4, 4), Fifteen.decodeParams("4x4"))
        assertEquals(Fifteen.Params(5, 3), Fifteen.decodeParams("5x3"))
        assertEquals(Fifteen.Params(4, 4), Fifteen.decodeParams("4"))
        assertEquals("6x2", Fifteen.encodeParams(Fifteen.Params(6, 2), true))

        assertEquals(null, Fifteen.validateParams(Fifteen.Params(2, 2), true))
        assertEquals(
            "Width and height must both be at least two",
            Fifteen.validateParams(Fifteen.Params(1, 4), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Fifteen.validateParams(Fifteen.Params(100000, 100000), true),
        )
    }

    @Test
    fun `descriptions are validated`() {
        val params = Fifteen.Params(2, 2)
        assertEquals(null, Fifteen.validateDesc(params, "1,2,3,0"))
        assertEquals(
            "Number used twice",
            Fifteen.validateDesc(params, "1,1,3,0"),
        )
        assertEquals(
            "Number out of range",
            Fifteen.validateDesc(params, "1,2,4,0"),
        )
        assertEquals(
            "Expected comma after number",
            Fifteen.validateDesc(params, "1 2,3,0"),
        )
        assertEquals(
            "Excess junk at end of string",
            Fifteen.validateDesc(params, "1,2,3,0x"),
        )
        assertEquals(
            "Expected comma after number",
            Fifteen.validateDesc(params, "1,2,3"),
        )
    }

    @Test
    fun `a solved save round-trips through the midend`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val save = midend.serialise()
        val loaded = Midend.fromSave(Fifteen, save)
        assertEquals(midend.id(), loaded.id())
        assertEquals(save.serialise(), loaded.serialise().serialise())
        assertEquals(midend.state, loaded.state)
    }

    @Test
    fun `moves solve undo and redo`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val start = midend.state
        assertEquals(0, midend.status())
        assertFalse(midend.canUndo)

        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertTrue(midend.canUndo)
        assertFalse(midend.canRedo)
        assertTrue(midend.undo())
        assertEquals(start, midend.state)
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertEquals("Moves since auto-solve: 0", midend.statusText())
    }

    @Test
    fun `the hint solver finishes a game`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        var steps = 0
        val limit = 5 * 16 * 16 * 16
        while (midend.status() == 0 && steps < limit) {
            midend.processKey(0, 0, 'h'.code, Fifteen.preferredTileSize)
            steps++
        }
        assertEquals(1, midend.status())
        val solved = Fifteen.newState(
            Fifteen.Params(4, 4),
            "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,0",
        )
        assertEquals(Fifteen.textFormat(solved), midend.textFormat())
    }

    @Test
    fun `a game id and a restart after a move round-trip`() {
        val id = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        val midend = Midend.fromGameId(Fifteen, id)
        assertEquals(id, midend.id())
        assertEquals(1, midend.stateCount)

        // Move tile 14 into the gap. This does not solve the game.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48),
        )
        assertEquals(2, midend.statePos)
        assertEquals(0, midend.status())

        assertTrue(midend.restart())
        assertEquals(3, midend.stateCount)
        assertEquals(3, midend.statePos)
        assertEquals(1, midend.serialise().values("RESTART").size)
        assertTrue(midend.undo())
        assertEquals(2, midend.statePos)
        assertTrue(midend.canRedo)
    }

    @Test
    fun `restart at the first state changes nothing`() {
        val midend = Midend.fromGameId(
            Fifteen,
            "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15",
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(96, 192, Buttons.LEFT_BUTTON, 48),
        )
        assertTrue(midend.undo())
        assertEquals(1, midend.statePos)
        assertTrue(midend.canRedo, "the redo branch exists")
        assertFalse(midend.takeCompletedEvent())

        // `midend_restart_game` returns immediately at the first state.
        val save = midend.serialise().serialise()
        assertFalse(midend.restart(), "a restart at the first state does nothing")
        assertEquals(save, midend.serialise().serialise(), "the save is byte-identical")
        assertEquals(1, midend.statePos)
        assertEquals(2, midend.stateCount)
        assertFalse(midend.canUndo)
        assertTrue(midend.canRedo, "the restart must not purge the redo branch")
        assertFalse(midend.takeCompletedEvent(), "no completion event is raised")
    }

    @Test
    fun `the shell solve and history keys work`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_SOLVE, Fifteen.preferredTileSize))
        assertEquals(1, midend.status())
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_UNDO, Fifteen.preferredTileSize))
        assertEquals(0, midend.status())
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, Buttons.UI_REDO, Fifteen.preferredTileSize))
        assertEquals(1, midend.status())
    }

    @Test
    fun `input uses the displayed tile size`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val tile = 82
        val x = tile + tile / 2
        val y = 3 * tile + tile / 2
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(x, y, Buttons.LEFT_BUTTON, tile),
        )
        assertTrue(midend.canUndo)
    }

    @Test
    fun `input follows the drawn cell centers`() {
        val desc = "1,2,3,4,5,0,7,8,9,10,11,12,13,14,6,15"
        val id = "4x4:$desc"
        val neighbours = listOf(0 to 1, 2 to 1, 1 to 0, 1 to 2)

        for (tile in listOf(48, 63, 82, 100)) {
            val sink = UpdateSink()
            val midend = Midend.fromGameId(Fifteen, id)
            Fifteen.redraw(midend.state, midend.ui, Game.NoDrawState, sink, tile)
            val cells = sink.updates.associateBy { it.x to it.y }
            assertEquals(16, cells.size, "tile $tile")

            for ((column, row) in neighbours) {
                val cell = cells[column * tile + tile / 2 to row * tile + tile / 2]
                    ?: error("no update for ($column,$row) at tile $tile")
                val fresh = Midend.fromGameId(Fifteen, id)
                assertEquals(
                    KeyOutcome.SomeEffect,
                    fresh.processKey(
                        cell.x + tile / 2,
                        cell.y + tile / 2,
                        Buttons.LEFT_BUTTON,
                        tile,
                    ),
                    "neighbour ($column,$row) at tile $tile",
                )
            }

            val gap = cells[1 * tile + tile / 2 to 1 * tile + tile / 2]!!
            val fresh = Midend.fromGameId(Fifteen, id)
            assertEquals(
                KeyOutcome.NoEffect,
                fresh.processKey(
                    gap.x + tile / 2,
                    gap.y + tile / 2,
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
                "gap click at tile $tile",
            )
        }
    }

    @Test
    fun `a winning move raises the completion event once`() {
        val desc = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        val midend = Midend.fromGameId(Fifteen, "4x4:$desc")
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent(), "a fresh game has no completion")

        val x = 24 + 3 * 48 + 24
        val y = 24 + 3 * 48 + 24
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(x, y, Buttons.LEFT_BUTTON, 48),
        )
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent(), "the winning move completes the game")
        assertFalse(midend.takeCompletedEvent(), "the event is consumed once")
    }

    @Test
    fun `a no-effect key does not raise the completion event`() {
        val desc = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        val midend = Midend.fromGameId(Fifteen, "4x4:$desc")

        // Click the gap: the game ignores the move and adds no state.
        val x = 24 + 2 * 48 + 24
        val y = 24 + 3 * 48 + 24
        assertEquals(
            KeyOutcome.NoEffect,
            midend.processKey(x, y, Buttons.LEFT_BUTTON, 48),
        )
        assertFalse(midend.takeCompletedEvent())
    }

    @Test
    fun `a solve does not raise the completion event`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        assertTrue(midend.solve())
        assertEquals(1, midend.status())
        assertFalse(midend.takeCompletedEvent(), "solving is not a completion")
    }

    @Test
    fun `undo and redo cross the completion boundary`() {
        val desc = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15"
        val midend = Midend.fromGameId(Fifteen, "4x4:$desc")
        val x = 24 + 3 * 48 + 24
        val y = 24 + 3 * 48 + 24
        midend.processKey(x, y, Buttons.LEFT_BUTTON, 48)
        assertTrue(midend.takeCompletedEvent())
        assertEquals(1, midend.status())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent(), "undo leaves the completed state")

        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent(), "redo enters the completed state")
        assertFalse(midend.takeCompletedEvent(), "the event is consumed once")
    }

    @Test
    fun `status text takes the ui and stays inside the midend redraw`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        assertEquals(Fifteen.statusText(midend.state, midend.ui), midend.statusText())

        val sink = UpdateSink()
        midend.recordRedraw(sink)
        assertEquals(listOf(midend.statusText()), sink.statuses)
    }

    private class UpdateSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        data class Update(val x: Int, val y: Int)
        val updates = ArrayList<Update>()
        val statuses = ArrayList<String>()

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}
        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {}
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
        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(x: Int, y: Int, font: Int, size: Int, align: Int, colour: Int, text: String) {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {
            updates += Update(x, y)
        }
        override fun status(text: String) {
            statuses += text
        }
        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }

    @Test
    fun `invalid moves are rejected`() {
        val state = Fifteen.newState(Fifteen.Params(4, 4), "1,2,3,4,5,6,7,8,9,10,11,12,13,14,0,15")
        assertNull(Fifteen.executeMove(state, "M4,4"))
        assertNull(Fifteen.executeMove(state, "M1,1"))
        assertNull(Fifteen.executeMove(state, "not a move"))
        assertNotNull(Fifteen.executeMove(state, "M3,3"))
    }

    @Test
    fun `the text format is a right-aligned grid`() {
        val state = Fifteen.newState(Fifteen.Params(2, 2), "1,2,3,0")
        assertEquals("1 2\n3  \n", Fifteen.textFormat(state))
    }

    @Test
    fun `the palette matches the upstream grey board`() {
        val colours = Fifteen.colours()
        assertEquals(12, colours.size)
        assertEquals(0.8f, colours[0])
        assertEquals(0.0f, colours[3])
        assertEquals(967, (colours[6] * 1000.0f).roundToInt())
        assertEquals(633, (colours[9] * 1000.0f).roundToInt())
    }

    @Test
    fun `an invalid save is rejected`() {
        val records = listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Fifteen"),
            SaveRecord("PARAMS", "1x1"),
            SaveRecord("CPARAMS", "1x1"),
            SaveRecord("DESC", "0"),
            SaveRecord("NSTATES", "1"),
            SaveRecord("STATEPOS", "1"),
        )
        assertFailsWith<IllegalStateException> {
            Midend.fromSave(Fifteen, SgtpSave.of(records))
        }
    }
}
