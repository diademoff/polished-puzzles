package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sized trace helper must read the board size through the UI-aware
 * `computeSize`. A game can choose its border from a UI preference, so
 * the two-argument form is not enough. The test-only game below rejects
 * the two-argument form and delegates the UI-aware form to pearl with
 * the Loopy appearance.
 */
class KotlinTraceSinkTest {

    private object UiSizedGame : Game by Pearl {
        override fun computeSize(params: Game.Params, tileSize: Int): Size =
            error("the sized trace helper must pass the UI")

        override fun computeSize(params: Game.Params, ui: Game.Ui, tileSize: Int): Size =
            Pearl.computeSize(params, ui, tileSize)
    }

    @Test
    fun `the sized trace helper reads the size from the ui`() {
        val midend = Midend.fromGameId(UiSizedGame, "8x8:zzl")
        (midend.ui as Pearl.Ui).appearance = Pearl.Appearance.LOOPY

        val trace = KotlinTraceSink.stateTrace(midend, 31)
        assertEquals(254L, trace.number("w"))
        assertEquals(254L, trace.number("h"))

        // The header and the midend's first-draw clear use one size.
        val clear = (trace.field("ops") as JsonValue.Arr).items
            .map { it as JsonValue.Obj }
            .single {
                it.string("op") == "rect" &&
                    it.number("x") == 0L &&
                    it.number("y") == 0L &&
                    it.number("c") == 0L
            }
        assertEquals(254L, clear.number("w"))
        assertEquals(254L, clear.number("h"))
    }
}
