package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. The timed-game seam of ADR 0007 (S5), pinned with a
 * test-only game that delegates to `fifteen`.
 *
 * The C midend reads `is_timed` and `timing_state`, keeps `elapsed` and
 * `timing`, switches the shell timer, prefixes the status text with
 * `[m:ss] `, and writes the `TIME` record. `mines` is the first real
 * timed game; the test game lets the seam be checked without that port.
 */
class TimerTest {

    /**
     * A timed game with no timer rule of its own. [running] is the
     * answer of `timing_state`, so a test can start and stop the clock
     * the way a game state change does.
     */
    private class TimedFifteen : Game by Fifteen {
        var running = false

        override val isTimed: Boolean get() = true

        override fun timingState(state: Game.State, ui: Game.Ui): Boolean = running

        // A UI record puts a record between `AUXINFO` and `TIME`, so the
        // test can pin the save order against a real neighbour.
        override fun encodeUi(ui: Game.Ui): String = "u"
    }

    @Test
    fun `the callback fires when the clock starts and stops`() {
        val game = TimedFifteen()
        val midend = Midend.newGame(game, "4x4", "11111")
        val calls = ArrayList<Boolean>()

        midend.timerCallback = { calls += it }
        assertEquals(
            listOf(false),
            calls,
            "the setter must sync the shell with the current timing value",
        )

        game.running = true
        assertFalse(midend.timerTick(0f))
        assertTrue(midend.timing)
        assertEquals(listOf(false, true), calls, "false to true must call the callback")

        midend.timerTick(0.5f)
        assertEquals(
            listOf(false, true),
            calls,
            "an unchanged timing value must not call the callback",
        )

        game.running = false
        midend.timerTick(0f)
        assertFalse(midend.timing)
        assertEquals(listOf(false, true, false), calls, "true to false must call the callback")
    }

    @Test
    fun `timerTick accumulates and reports whole seconds`() {
        val game = TimedFifteen().apply { running = true }
        val midend = Midend.newGame(game, "4x4", "11111")
        assertTrue(midend.timing, "a running clock starts at construction")

        assertFalse(midend.timerTick(0.25f))
        assertEquals(0.25f, midend.elapsed)
        assertTrue(midend.timerTick(0.75f), "one second has passed")
        assertEquals(1.0f, midend.elapsed)
        assertFalse(midend.timerTick(0.5f), "the whole second is still 1")
        assertEquals(1.5f, midend.elapsed)
        assertTrue(midend.timerTick(59.5f), "the whole second changed again")
        assertEquals(61.0f, midend.elapsed)
    }

    @Test
    fun `a stopped clock ignores ticks`() {
        val game = TimedFifteen()
        val midend = Midend.newGame(game, "4x4", "11111")

        assertFalse(midend.timerTick(10f))
        assertEquals(0f, midend.elapsed)
    }

    @Test
    fun `statusText prefixes the elapsed time`() {
        val game = TimedFifteen().apply { running = true }
        val midend = Midend.newGame(game, "4x4", "11111")
        val text = Fifteen.statusText(midend.state, midend.ui)

        assertEquals("[0:00] $text", midend.statusText())
        midend.timerTick(5f)
        assertEquals("[0:05] $text", midend.statusText())
        midend.timerTick(60f)
        assertEquals("[1:05] $text", midend.statusText())
    }

    @Test
    fun `TIME round-trips through a save in both directions`() {
        val game = TimedFifteen().apply { running = true }
        val midend = Midend.newGame(game, "4x4", "11111")
        midend.timerTick(12.5f)

        val save = midend.serialise()
        assertEquals("12.5", save.value("TIME"), "C writes the record with %g")
        val keys = save.records.map { it.key }
        assertTrue(keys.indexOf("UI") < keys.indexOf("TIME"), "TIME follows UI")
        assertEquals(
            keys.indexOf("NSTATES") - 1,
            keys.indexOf("TIME"),
            "TIME comes directly before NSTATES",
        )

        val text = save.serialise()
        val reloaded = Midend.fromSave(game, SgtpSave.parse(text))
        assertEquals(12.5f, reloaded.elapsed)
        assertEquals(text, reloaded.serialise().serialise())
    }

    @Test
    fun `a save without TIME loads at zero`() {
        val game = TimedFifteen().apply { running = true }
        val save = Midend.newGame(game, "4x4", "11111").serialise()
        val without = SgtpSave.of(save.records.filterNot { it.key == "TIME" })

        val reloaded = Midend.fromSave(game, without)
        assertEquals(0f, reloaded.elapsed)
        assertEquals("0", reloaded.serialise().value("TIME"))
    }

    @Test
    fun `an untimed game never runs the clock and writes no TIME`() {
        val midend = Midend.newGame(Fifteen, "4x4", "11111")
        val calls = ArrayList<Boolean>()

        midend.timerCallback = { calls += it }
        assertEquals(
            listOf(false),
            calls,
            "the setter syncs the shell once, with the stopped clock",
        )
        calls.clear()

        assertFalse(midend.timerTick(10f))
        assertEquals(0f, midend.elapsed)
        assertFalse(midend.timing)
        assertTrue(calls.isEmpty(), "an untimed game must not call the timer callback")
        assertNull(midend.serialise().value("TIME"), "an untimed save has no TIME record")
    }
}
