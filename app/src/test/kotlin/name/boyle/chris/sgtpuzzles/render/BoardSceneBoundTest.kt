package name.boyle.chris.sgtpuzzles.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict, JVM. A frame stays the size of one board however long
 * the session is.
 *
 * A pass that repaints the whole window makes the passes before it
 * invisible, so the recorder drops them; `BoardCanvas` then draws one
 * pass per frame instead of the whole session. A pass that draws only a
 * change must still be added, because the game relies on the frontend
 * to keep the rest (ADR 0010).
 *
 * The shapes are `fifteen` 4x4: two border polygons and four operations
 * per tile on a full pass.
 */
class BoardSceneBoundTest {

    private val board = BoardRecorder()
    private var frame: BoardFrame? = null
    private var blitterId = -1

    init {
        board.onFrame = { frame = it }
    }

    /** A first pass of a new drawstate: the clear, the board, a blitter. */
    private fun drawFullPass(withClear: Boolean = true) {
        board.startDraw()
        if (withClear) board.fillRect(0, 0, BOARD, BOARD, 0)
        board.drawPoly(1f, intArrayOf(0, 0, BOARD, 0, BOARD, BOARD), 0, 0, 2, 2)
        for (i in 0 until TILES) drawTile(i)
        board.blitterSave(blitterId, TILE, TILE)
        board.endDraw()
    }

    /** A move: the two changed tiles and a blitter restore. */
    private fun drawMove() {
        board.startDraw()
        board.blitterLoad(blitterId, TILE, TILE)
        drawTile(0)
        drawTile(1)
        board.blitterSave(blitterId, TILE, TILE)
        board.endDraw()
    }

    private fun drawTile(index: Int) {
        val x = (index % SIDE) * TILE
        val y = (index / SIDE) * TILE
        board.fillRect(x, y, TILE, TILE, 0)
        board.drawPoly(1f, intArrayOf(x, y, x + TILE, y, x, y + TILE), 0, 0, 1, 1)
        board.drawText(x + TILE / 2, y + TILE / 2, 0x101, 16, 3, "${index + 1}")
    }

    @Test
    fun `a long session of full passes keeps the scene at one pass`() {
        blitterId = board.blitterAlloc(TILE, TILE)
        drawFullPass()
        val onePass = frame!!.ops.size
        val blitters = frame!!.blitters.size

        for (move in 1..300) {
            drawFullPass()
            val ops = frame!!.ops
            assertEquals("pass $move is one board", onePass, ops.size)
            assertEquals("pass $move keeps the blitter table", blitters, frame!!.blitters.size)
            assertEquals(
                "pass $move starts with the window clear",
                DrawOp.FillRect(0, 0, BOARD, BOARD, 0),
                ops.first(),
            )
        }
    }

    @Test
    fun `a change is added until a full pass drops it`() {
        blitterId = board.blitterAlloc(TILE, TILE)
        drawFullPass()
        val onePass = frame!!.ops.size

        drawMove()
        assertTrue(
            "a change needs the pass before it",
            frame!!.ops.size > onePass,
        )

        drawFullPass()
        assertEquals("the next full pass drops the change", onePass, frame!!.ops.size)
    }

    @Test
    fun `a reset drops the scene`() {
        blitterId = board.blitterAlloc(TILE, TILE)
        drawFullPass()
        board.reset()
        drawMove()
        assertEquals(
            "a pass after a reset holds only its own operations",
            7,
            frame!!.ops.size,
        )
    }

    private companion object {
        const val SIDE = 4
        const val TILE = 48
        const val BOARD = SIDE * TILE
        const val TILES = SIDE * SIDE
    }
}
