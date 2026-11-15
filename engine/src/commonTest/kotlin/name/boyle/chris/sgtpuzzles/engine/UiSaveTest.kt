package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Level: semantic.
 *
 * A game may store frontend state in the save file's `UI` record, as
 * guess and blackbox do. The record is written after `AUXINFO` and
 * before `NSTATES`, and the UI stays out of the state, the history,
 * and the game ID.
 */
class UiSaveTest {

    private val tileSize = 48

    /** A small printable UI: two counters and a flag. */
    private class TestUi(
        var first: Int = 0,
        var second: Int = 0,
        var flag: Boolean = false,
    ) : Game.Ui {
        fun encode(): String = "$first,$second,${if (flag) 1 else 0}"

        override fun equals(other: Any?): Boolean =
            other is TestUi &&
                other.first == first &&
                other.second == second &&
                other.flag == flag

        override fun hashCode(): Int = (first * 31 + second) * 2 + if (flag) 1 else 0

        override fun toString(): String = "TestUi(first=$first, second=$second, flag=$flag)"

        companion object {
            fun decode(text: String): TestUi {
                val parts = text.split(',')
                require(parts.size == 3) { "test UI needs three fields: '$text'" }
                return TestUi(parts[0].toInt(), parts[1].toInt(), parts[2] == "1")
            }
        }
    }

    /**
     * Fifteen with a UI record. The key `x` changes only the UI, so a
     * test can make the UI differ from what `newUi` would create.
     */
    private class UiFifteen(private val writeUi: Boolean = true) : Game by Fifteen {

        var decodedState: Game.State? = null
            private set

        override fun newUi(state: Game.State): Game.Ui = TestUi()

        override fun encodeUi(ui: Game.Ui): String? =
            if (writeUi) (ui as TestUi).encode() else null

        override fun decodeUi(state: Game.State, text: String): Game.Ui {
            decodedState = state
            return TestUi.decode(text)
        }

        override fun interpretMove(
            state: Game.State,
            ui: Game.Ui,
            x: Int,
            y: Int,
            button: Int,
            tileSize: Int,
        ): MoveResult {
            if (button == 'x'.code) {
                val testUi = ui as TestUi
                testUi.first++
                testUi.second += 2
                testUi.flag = !testUi.flag
                return MoveResult.UiUpdate
            }
            return Fifteen.interpretMove(state, ui, x, y, button, tileSize)
        }
    }

    /** Clicks a tile next to the gap, which is one real move. */
    private fun Midend.clickNextToGap() {
        val state = state as Fifteen.State
        val gapX = state.gapPos % state.width
        val gapY = state.gapPos / state.width
        val targetX = if (gapX + 1 < state.width) gapX + 1 else gapX - 1
        processKey(
            targetX * tileSize + tileSize / 2,
            gapY * tileSize + tileSize / 2,
            Buttons.LEFT_BUTTON,
            tileSize,
        )
    }

    @Test
    fun `a ui record round-trips with the state`() {
        val game = UiFifteen()
        val midend = Midend.newGame(game, "4x4", "12345")
        val start = midend.state
        midend.processKey(0, 0, 'x'.code, tileSize)
        midend.clickNextToGap()

        val text = midend.serialise().serialise()
        assertEquals(true, text.contains("UI      :5:1,2,1\n"), text)

        val reloaded = Midend.fromSave(game, SgtpSave.parse(text))
        assertEquals(TestUi(1, 2, true), reloaded.ui)
        assertEquals(reloaded.state, game.decodedState)
        assertEquals(midend.state, reloaded.state)
        assertEquals(midend.id(), reloaded.id())
        assertEquals(midend.status(), reloaded.status())
        assertEquals(midend.textFormat(), reloaded.textFormat())
        assertEquals(midend.stateCount, reloaded.stateCount)
        assertEquals(midend.statePos, reloaded.statePos)
        assertEquals(text, reloaded.serialise().serialise())

        assertEquals(true, reloaded.undo())
        assertEquals(start, reloaded.state)
    }

    @Test
    fun `the ui is not in the state, the history, or the game id`() {
        val game = UiFifteen()
        val midend = Midend.newGame(game, "4x4", "12345")
        val state = midend.state
        val id = midend.id()
        val status = midend.status()
        val textFormat = midend.textFormat()

        midend.processKey(0, 0, 'x'.code, tileSize)

        assertNotEquals(game.newUi(midend.state), midend.ui)
        assertEquals(state, midend.state)
        assertEquals(id, midend.id())
        assertEquals(status, midend.status())
        assertEquals(textFormat, midend.textFormat())
        assertEquals(1, midend.stateCount)
        assertEquals(1, midend.statePos)
    }

    @Test
    fun `a save without a ui record uses a fresh ui`() {
        val writer = UiFifteen(writeUi = false)
        val midend = Midend.newGame(writer, "4x4", "12345")
        midend.processKey(0, 0, 'x'.code, tileSize)
        val save = midend.serialise()
        val text = save.serialise()
        assertNull(save.value("UI"))

        val game = UiFifteen()
        val reloaded = Midend.fromSave(game, SgtpSave.parse(text))
        assertEquals(TestUi(), reloaded.ui)
        assertEquals(game.newUi(reloaded.state), reloaded.ui)
    }

    @Test
    fun `the ui record comes after auxinfo and before nstates`() {
        val game = UiFifteen()
        val midend = Midend.newGame(game, "4x4", "12345")
        val auxInfo = AuxInfo.encode("answer")
        val records = midend.serialise().records.toMutableList()
        val nstates = records.indexOfFirst { it.key == "NSTATES" }
        records.add(nstates, SaveRecord("AUXINFO", auxInfo))

        val reloaded = Midend.fromSave(game, SgtpSave.of(records)).serialise()
        assertEquals(
            listOf(
                "SAVEFILE",
                "VERSION",
                "GAME",
                "PARAMS",
                "CPARAMS",
                "SEED",
                "DESC",
                "AUXINFO",
                "UI",
                "NSTATES",
                "STATEPOS",
            ),
            reloaded.records.map { it.key },
        )
        assertEquals(auxInfo, reloaded.value("AUXINFO"))
        assertEquals("0,0,0", reloaded.value("UI"))
    }

    @Test
    fun `a non-printable ui value is rejected`() {
        val coffee = object : Game by Fifteen {
            override fun encodeUi(ui: Game.Ui): String? = "caf\u00e9"
        }
        assertFailsWith<IllegalArgumentException> {
            Midend.newGame(coffee, "4x4", "12345").serialise()
        }

        val control = object : Game by Fifteen {
            override fun encodeUi(ui: Game.Ui): String? = "1,\u0001"
        }
        assertFailsWith<IllegalArgumentException> {
            Midend.newGame(control, "4x4", "12345").serialise()
        }
    }
}
