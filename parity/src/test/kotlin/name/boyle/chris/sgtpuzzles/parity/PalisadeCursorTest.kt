package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: semantic.
 *
 * The instrumentation case `GamePlayParameterizedTest[Palisade
 * 2x3n3:d33]` used `L`, which only toggles an edge in the legacy
 * half-grid cursor. Palisade defaults to the full-grid cursor. This
 * test pins the default-mode sequence at the engine level, so the stale
 * case cannot come back silently.
 */
class PalisadeCursorTest {

    private companion object {
        const val CURSOR_DOWN = 0x020A
        const val CURSOR_RIGHT = 0x020C
        const val SELECT = '\n'.code
    }

    @Test
    fun `the full-grid cursor sequence completes the puzzle`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("palisade", "2x3n3", "d33")
            assertEquals(0, start.status)

            // Cursor starts on the centre of the top-left cell. Move to
            // the vertical edge between the columns, select it, then go
            // to the next row. The three vertical edges split the board
            // into two columns of three cells, which is the solution.
            val end = oracle.keys(
                start.state,
                listOf(
                    CURSOR_RIGHT, SELECT,
                    CURSOR_DOWN, CURSOR_DOWN, SELECT,
                    CURSOR_DOWN, CURSOR_DOWN, SELECT,
                ),
            )

            assertEquals(1, end.status)
        }
}
