package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. Pins deviation D-002 in docs/DEVIATIONS.md.
 *
 * The app compiles with SMALL_SCREEN, so four games expose fewer
 * presets than a desktop upstream build. If the define is removed, or
 * if a game changes its preset list, this test fails and the deviation
 * must be reviewed.
 */
class PresetDeviationTest {

    @Test
    fun `android presets omit the desktop large presets`() = OracleClient().use { oracle ->
        assertEquals("loopy", 20, oracle.presets("loopy").size)
        assertEquals("mines", 4, oracle.presets("mines").size)
        assertEquals("net", 8, oracle.presets("net").size)
        assertEquals("rect", 5, oracle.presets("rect").size)

        val mines = oracle.presets("mines").map { it.params }
        assertTrue("30x16 must not be a preset: $mines", mines.none { it.startsWith("30x16") })
    }
}
