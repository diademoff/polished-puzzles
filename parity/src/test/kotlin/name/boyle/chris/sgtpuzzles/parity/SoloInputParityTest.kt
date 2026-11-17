package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Solo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: solo.
 *
 * Layout, real pointer clicks and digits at several tile sizes, a
 * letterboxed view, the pencil and right-button selection, the clear
 * key, the mark key, the cursor and modified cursor keys, a keypad
 * digit, undo and redo, and a real completion. Every real move is
 * compared by save and by the C text form; every inert event must change
 * neither.
 */
class SoloInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 120_000)
    fun `layout and computeSize match the oracle`() = OracleClient().use { oracle ->
        for (case in viewports) {
            val start = oracle.newGame("solo", case.params, SEED)
            val layout = oracle.layout(start.state, case.width, case.height)
            val midend = Midend.newGame(Solo, case.params, SEED)
            val geometry = midend.geometryFor(case.width, case.height)
            val size = Solo.computeSize(Solo.decodeParams(case.params), geometry.tileSize)
            val where = "${case.label}: '${case.params}' in ${case.width} x ${case.height}"

            assertEquals("tile $where", case.tile, geometry.tileSize)
            assertEquals("computeSize width $where", layout.width, size.width)
            assertEquals("computeSize height $where", layout.height, size.height)
            assertEquals("layout width $where", layout.width, geometry.width)
            assertEquals("layout height $where", layout.height, geometry.height)
            assertEquals(
                "origin x $where",
                (case.width - layout.width) / 2,
                geometry.originX,
            )
            assertEquals(
                "origin y $where",
                (case.height - layout.height) / 2,
                geometry.originY,
            )
        }
    }

    @Test(timeout = 300_000)
    fun `one click and one digit fill the same cell at every view`() = OracleClient().use { oracle ->
        for (case in viewports) {
            val start = oracle.newGame("solo", case.params, SEED)
            val midend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            val state = midend.state as Solo.State
            val geometry = midend.geometryFor(case.width, case.height)
            val tile = geometry.tileSize
            val (x, y) = firstEmpty(state, case.params)
            val viewX = geometry.originX + x * tile + tile / 2
            val viewY = geometry.originY + y * tile + tile / 2
            val gameX = geometry.toGameX(viewX)
            val gameY = geometry.toGameY(viewY)
            val where = "${case.label} at ($viewX,$viewY) tile $tile origin " +
                "(${geometry.originX},${geometry.originY})"

            val fillDigit = minOf(5, (Solo.decodeParams(case.params) as Solo.Params).let { it.c * it.r })
            val startSave = midend.serialise().serialise()
            val before = state.grid.copyOf()
            val expected = oracle.pointsSized(
                start.state,
                listOf(
                    OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                    OracleClient.Point(viewX, viewY, '0'.code + fillDigit),
                ),
                case.width,
                case.height,
            )

            assertEquals(
                "click outcome $where",
                KeyOutcome.SomeEffect,
                midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, tile),
            )
            assertTrue("a click leaves the grid $where", before.contentEquals((midend.state as Solo.State).grid))
            assertEquals("a click keeps the save $where", startSave, midend.serialise().serialise())
            assertEquals("a click adds no state $where", 1, midend.stateCount)
            assertEquals(
                "digit outcome $where",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, '0'.code + fillDigit, tile),
            )
            assertEquals("save $where", expected.state.state, midend.serialise().serialise())
            assertEquals("text $where", oracle.text(expected.state.state), midend.textFormat())
            assertEquals("status $where", expected.state.status, midend.status())
            assertEquals("one move $where", 1, SgtpSave.parse(midend.serialise().serialise()).values("MOVE").size)
            assertFalse("a real fill changes the grid $where", before.contentEquals((midend.state as Solo.State).grid))
            assertEquals("a real fill adds one state $where", 2, midend.stateCount)
        }
    }

    @Test(timeout = 300_000)
    fun `letterbox and clue clicks are inert`() = OracleClient().use { oracle ->
        for (case in viewports + letterboxViews) {
            val start = oracle.newGame("solo", case.params, SEED)
            val midend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            val state = midend.state as Solo.State
            val geometry = midend.geometryFor(case.width, case.height)
            val tile = geometry.tileSize
            val where = "${case.label} in ${case.width} x ${case.height}"
            val save = midend.serialise().serialise()

            // A click outside the board is a UI update and no move.
            val expected = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(0, 0, Buttons.LEFT_BUTTON)),
                case.width,
                case.height,
            )
            assertEquals("letterbox keeps C's save $where", save, expected.state.state)
            assertEquals(
                "letterbox outcome $where",
                KeyOutcome.SomeEffect,
                midend.processKey(
                    -geometry.originX,
                    -geometry.originY,
                    Buttons.LEFT_BUTTON,
                    tile,
                ),
            )
            assertEquals("letterbox keeps the save $where", save, midend.serialise().serialise())
            assertEquals("letterbox adds no state $where", 1, midend.stateCount)

            // A clue click on a left or right button hides the highlight
            // and changes nothing.
            val clue = firstClue(state)
            if (clue != null) {
                val viewX = geometry.originX + clue.first * tile + tile / 2
                val viewY = geometry.originY + clue.second * tile + tile / 2
                for (button in listOf(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON)) {
                    val clicked = oracle.pointsSized(
                        start.state,
                        listOf(OracleClient.Point(viewX, viewY, button)),
                        case.width,
                        case.height,
                    )
                    assertEquals("clue click keeps C's save $where $button", save, clicked.state.state)
                    val clueMidend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
                    assertEquals(
                        "clue click $where $button",
                        KeyOutcome.SomeEffect,
                        clueMidend.processKey(
                            geometry.toGameX(viewX),
                            geometry.toGameY(viewY),
                            button,
                            tile,
                        ),
                    )
                    assertEquals("clue click keeps the save $where $button", save, clueMidend.serialise().serialise())
                    assertEquals("clue click adds no state $where $button", 1, clueMidend.stateCount)
                }
            }

            // A middle click and a drag and release are inert.
            val (ex, ey) = firstEmpty(state, case.params)
            val viewX = geometry.originX + ex * tile + tile / 2
            val viewY = geometry.originY + ey * tile + tile / 2
            val gameX = geometry.toGameX(viewX)
            val gameY = geometry.toGameY(viewY)
            val dragPoints = listOf(
                OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                OracleClient.Point(viewX, viewY, Buttons.LEFT_DRAG),
                OracleClient.Point(viewX, viewY, Buttons.LEFT_RELEASE),
            )
            val dragged = oracle.pointsSized(start.state, dragPoints, case.width, case.height)
            val dragMidend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            for (p in dragPoints) {
                dragMidend.processKey(
                    geometry.toGameX(p.x),
                    geometry.toGameY(p.y),
                    p.button,
                    tile,
                )
            }
            assertEquals("drag keeps C's save $where", dragged.state.state, dragMidend.serialise().serialise())
            assertEquals("drag adds no state $where", 1, dragMidend.stateCount)

            // A digit with no selection is unused.
            val digit = oracle.pointsSized(
                start.state,
                listOf(OracleClient.Point(0, 0, '5'.code)),
                case.width,
                case.height,
            )
            assertEquals("digit keeps C's save $where", save, digit.state.state)
            val digitMidend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            assertEquals(
                "digit outcome $where",
                KeyOutcome.Unused,
                digitMidend.processKey(0, 0, '5'.code, tile),
            )
            assertEquals("digit keeps the save $where", save, digitMidend.serialise().serialise())
        }
    }

    @Test(timeout = 300_000)
    fun `pencil clear and mark match the oracle`() = OracleClient().use { oracle ->
        for (case in listOf(viewports[0], viewports[4], letterboxViews[0])) {
            val start = oracle.newGame("solo", case.params, SEED)
            val geometry = Midend.fromSave(Solo, SgtpSave.parse(start.state))
                .geometryFor(case.width, case.height)
            val tile = geometry.tileSize
            val state = Midend.fromSave(Solo, SgtpSave.parse(start.state)).state as Solo.State
            val (x, y) = firstEmpty(state, case.params)
            val viewX = geometry.originX + x * tile + tile / 2
            val viewY = geometry.originY + y * tile + tile / 2
            val gameX = geometry.toGameX(viewX)
            val gameY = geometry.toGameY(viewY)
            val where = "${case.label} tile $tile"

            // Two left clicks toggle pencil mode, then a digit marks.
            val pencilPoints = listOf(
                OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '3'.code),
            )
            val midend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            val expected = oracle.pointsSized(start.state, pencilPoints, case.width, case.height)
            for (p in pencilPoints) {
                midend.processKey(geometry.toGameX(p.x), geometry.toGameY(p.y), p.button, tile)
            }
            assertEquals("pencil save $where", expected.state.state, midend.serialise().serialise())
            assertEquals("pencil text $where", oracle.text(expected.state.state), midend.textFormat())
            assertEquals(
                "pencil move $where",
                "P$x,$y,3",
                SgtpSave.parse(expected.state.state).value("MOVE"),
            )

            // Right click selects pencil mode.
            val rightPoints = listOf(
                OracleClient.Point(viewX, viewY, Buttons.RIGHT_BUTTON),
                OracleClient.Point(0, 0, '4'.code),
            )
            val rightMidend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            val rightExpected = oracle.pointsSized(start.state, rightPoints, case.width, case.height)
            for (p in rightPoints) {
                rightMidend.processKey(geometry.toGameX(p.x), geometry.toGameY(p.y), p.button, tile)
            }
            assertEquals("right pencil save $where", rightExpected.state.state, rightMidend.serialise().serialise())
            assertEquals("right pencil text $where", oracle.text(rightExpected.state.state), rightMidend.textFormat())

            // A real entry, then the clear key.
            val clearPoints = listOf(
                OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '5'.code),
                OracleClient.Point(viewX, viewY, Buttons.LEFT_BUTTON),
                OracleClient.Point(0, 0, '\b'.code),
            )
            val clearMidend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            val clearExpected = oracle.pointsSized(start.state, clearPoints, case.width, case.height)
            for (p in clearPoints) {
                clearMidend.processKey(geometry.toGameX(p.x), geometry.toGameY(p.y), p.button, tile)
            }
            assertEquals("clear save $where", clearExpected.state.state, clearMidend.serialise().serialise())
            assertEquals("clear text $where", oracle.text(clearExpected.state.state), clearMidend.textFormat())

            // Mark all.
            val markPoints = listOf(OracleClient.Point(0, 0, 'M'.code))
            val markMidend = Midend.fromSave(Solo, SgtpSave.parse(start.state))
            val markExpected = oracle.pointsSized(start.state, markPoints, case.width, case.height)
            for (p in markPoints) markMidend.processKey(0, 0, p.button, tile)
            assertEquals("mark save $where", markExpected.state.state, markMidend.serialise().serialise())
            assertEquals("mark text $where", oracle.text(markExpected.state.state), markMidend.textFormat())
        }
    }

    @Test(timeout = 300_000)
    fun `the keyboard matches the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("solo", "3x3", SEED).state
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT, '5'.code),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_DOWN, '7'.code),
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_SELECT, '3'.code),
            listOf(Buttons.CURSOR_RIGHT, '5'.code, '\b'.code),
            listOf(Buttons.CURSOR_RIGHT, '5'.code, Buttons.CURSOR_SELECT2),
            listOf('M'.code),
            listOf(Buttons.MOD_CTRL or Buttons.CURSOR_RIGHT, '5'.code),
            listOf('5'.code or Buttons.MOD_NUM_KEYPAD),
            listOf(Buttons.CURSOR_RIGHT, '5'.code, 'u'.code),
            listOf(Buttons.CURSOR_RIGHT, '5'.code, 'u'.code, 'r'.code),
        )
        for (seq in sequences) {
            val expected = oracle.keys(save, seq)
            val midend = Midend.fromSave(Solo, SgtpSave.parse(save))
            for (key in seq) midend.processKey(0, 0, key, 48)
            assertEquals("${seq.map { it.toString(16) }} save", expected.state, midend.serialise().serialise())
            assertEquals("${seq.map { it.toString(16) }} text", oracle.text(expected.state), midend.textFormat())
            assertEquals("${seq.map { it.toString(16) }} status", expected.status, midend.status())
        }
    }

    @Test(timeout = 300_000)
    fun `undo redo and completion match the oracle`() = OracleClient().use { oracle ->
        val base = oracle.newGame("solo", "3x3", SEED)
        val state = Midend.fromSave(Solo, SgtpSave.parse(base.state))
        val (x, y) = firstEmpty(state.state as Solo.State, "3x3")
        val points = listOf(
            OracleClient.Point((x + 1) * 48, (y + 1) * 48, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, '5'.code),
        )
        val played = oracle.pointsSized(base.state, points)
        for (p in points) state.processKey(p.x, p.y, p.button, 48)
        assertTrue(state.canUndo)

        val undone = oracle.undo(played.state.state)
        assertTrue(state.undo())
        assertEquals("undo save", undone.state, state.serialise().serialise())
        assertEquals("undo text", oracle.text(undone.state), state.textFormat())
        assertTrue(state.canRedo)
        val redone = oracle.redo(undone.state)
        assertTrue(state.redo())
        assertEquals("redo save", redone.state, state.serialise().serialise())

        // Fill every cell except the last, then complete with a real
        // click and digit.
        val solved = state.auxInfo!!.removePrefix("S").split(',').map { it.toInt() }
        val baseMidend = Midend.fromSave(Solo, SgtpSave.parse(base.state))
        val grid = (baseMidend.state as Solo.State).grid
        val empty = (0 until 81).filter { grid[it] == 0 }
        val last = empty.last()
        var text = base.state
        for (i in empty.dropLast(1)) {
            text = appendRecord(text, "MOVE", "R${i % 9},${i / 9},${solved[i]}")
        }
        val almost = Midend.fromSave(Solo, SgtpSave.parse(text))
        val lx = last % 9
        val ly = last / 9
        val finalPoints = listOf(
            OracleClient.Point((lx + 1) * 48, (ly + 1) * 48, Buttons.LEFT_BUTTON),
            OracleClient.Point(0, 0, '0'.code + solved[last]),
        )
        val expected = oracle.pointsSized(text, finalPoints)
        for (p in finalPoints) almost.processKey(p.x, p.y, p.button, 48)
        assertEquals("completion save", expected.state.state, almost.serialise().serialise())
        assertEquals("completion status", 1, almost.status())
        assertTrue("completion event", almost.takeCompletedEvent())
        assertFalse("completion event once", almost.takeCompletedEvent())
    }

    private fun firstEmpty(state: Solo.State, params: String): Pair<Int, Int> {
        for (y in 0 until state.cr) {
            for (x in 0 until state.cr) {
                if (state.valueAt(x, y) == 0) return x to y
            }
        }
        error("no empty cell")
    }

    private fun firstClue(state: Solo.State): Pair<Int, Int>? {
        for (y in 0 until state.cr) {
            for (x in 0 until state.cr) {
                if (state.isImmutable(x, y)) return x to y
            }
        }
        return null
    }

    private fun appendRecord(save: String, key: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        val next = states + 1
        val oldLength = states.toString().length
        val newLength = next.toString().length
        return save
            .replace("NSTATES :$oldLength:$states", "NSTATES :$newLength:$next")
            .replace("STATEPOS:$oldLength:$states", "STATEPOS:$newLength:$next") +
            "${key.padEnd(8)}:${move.length}:$move\n"
    }

    private data class Viewport(
        val label: String,
        val params: String,
        val width: Int,
        val height: Int,
        val tile: Int,
    )

    private val viewports: List<Viewport> = run {
        val list = ArrayList<Viewport>()
        for (params in listOf("2x2", "2x3db", "3x3", "9jdb", "3x3ka", "4x4db")) {
            for (tile in listOf(48, 63, 82, 100)) {
                val side = Solo.computeSize(Solo.decodeParams(params), tile).width
                list += Viewport("$params@$tile", params, side, side, tile)
            }
        }
        list
    }

    private val letterboxViews = listOf(
        Viewport("3x3-phone", "3x3", 411, 702, 41),
        Viewport("9jdb-phone", "9jdb", 411, 702, 41),
        Viewport("2x2-phone", "2x2", 411, 702, 82),
    )

    private companion object {
        const val SEED = "12345"
    }
}
