package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. Every game, first preset.
 *
 * The recorder must handle every operation that the real games emit, not
 * only the operations in the `fifteen` goldens. This test runs one
 * canonical trace for each game, so a new primitive or a broken escape
 * fails here with the game name. It does not pin the geometry; the
 * per-game goldens do that.
 */
class TraceRecorderTest {

    @Test
    fun `every game records a canonical trace`() = OracleClient().use { oracle ->
        val games = oracle.games().sorted()
        assertTrue("the oracle knows no games", games.isNotEmpty())

        for (game in games) {
            val params = oracle.presets(game).firstOrNull()?.params ?: ""
            val trace = oracle.traceNew(game, params, GenTraces.SEED)
            val text = TraceNormalizer.normalize(trace)

            assertTrue("$game '$params': no start_draw", text.contains("start_draw"))
            assertTrue("$game '$params': no end_draw", text.contains("end_draw"))
            assertTrue("$game '$params': no operations", text.lines().size > 6)

            // The same game, params, and seed must record the same trace.
            assertEquals("$game '$params' is not deterministic", text,
                TraceNormalizer.normalize(oracle.traceNew(game, params, GenTraces.SEED)))
        }
    }
}
