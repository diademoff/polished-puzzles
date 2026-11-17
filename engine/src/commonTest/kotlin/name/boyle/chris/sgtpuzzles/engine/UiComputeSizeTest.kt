package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Level: semantic. Game: a test-only game whose size reads the UI.
 *
 * Upstream `game_compute_size` takes a `game_ui`, because `pearl` picks
 * its border from the appearance preference (`pearl.c:2008`,
 * `pearl.c:2446-2447`). The board size is therefore not a function of
 * the parameters and the tile size alone. The midend, the size search,
 * the geometry, and the app must all pass the current UI.
 */
class UiComputeSizeTest {

    @Test
    fun `size uses the current ui`() {
        val wide = Midend.newGame(UiSizedGame(wide = true), "4x4", SEED)
        val narrow = Midend.newGame(UiSizedGame(wide = false), "4x4", SEED)

        assertEquals(Size(78, 202), wide.size, "wide board at the preferred tile")
        assertEquals(Size(202, 78), narrow.size, "narrow board at the preferred tile")
    }

    @Test
    fun `largest tile uses the current ui`() {
        val wide = Midend.newGame(UiSizedGame(wide = true), "4x4", SEED)
        val narrow = Midend.newGame(UiSizedGame(wide = false), "4x4", SEED)

        assertEquals(72, wide.largestTileFor(400, 300), "wide board search")
        assertEquals(97, narrow.largestTileFor(400, 300), "narrow board search")
    }

    @Test
    fun `geometry uses the current ui`() {
        val wide = Midend.newGame(UiSizedGame(wide = true), "4x4", SEED)
        assertEquals(
            BoardGeometry(tileSize = 72, originX = 149, originY = 1, width = 102, height = 298),
            wide.geometryFor(400, 300),
        )

        val narrow = Midend.newGame(UiSizedGame(wide = false), "4x4", SEED)
        assertEquals(
            BoardGeometry(tileSize = 97, originX = 1, originY = 86, width = 398, height = 127),
            narrow.geometryFor(400, 300),
        )
    }

    @Test
    fun `preferred geometry uses the current ui`() {
        val wide = Midend.newGame(UiSizedGame(wide = true), "4x4", SEED)
        assertEquals(
            BoardGeometry(tileSize = 48, originX = 0, originY = 0, width = 78, height = 202),
            BoardGeometry.preferred(wide.game, wide.params, wide.ui),
        )

        val narrow = Midend.newGame(UiSizedGame(wide = false), "4x4", SEED)
        assertEquals(
            BoardGeometry(tileSize = 48, originX = 0, originY = 0, width = 202, height = 78),
            BoardGeometry.preferred(narrow.game, narrow.params, narrow.ui),
        )
    }

    /**
     * A game that does not read the UI keeps its size. The three-argument
     * form is a default, so every ported game answers the same as the
     * two-argument form that its own tests pin.
     */
    @Test
    fun `a game that ignores the ui keeps its size`() {
        val games = listOf(Fifteen, Sixteen, Slant, SameGame, Mines, Tracks)
        for (game in games) {
            val params = game.defaultParams()
            for (tile in listOf(20, 31, 48, 63, 100)) {
                assertEquals(
                    game.computeSize(params, tile),
                    game.computeSize(params, Game.NoUi, tile),
                    "${game.backendName} at tile $tile",
                )
            }
        }
    }

    private companion object {
        const val SEED = "12345"
    }

    /** A UI with one flag, standing in for `pearl`'s appearance pref. */
    private data class TestUi(val wide: Boolean) : Game.Ui

    /**
     * A test-only game whose size is a different shape per flag, so a
     * dropped UI changes the answer visibly.
     */
    private class UiSizedGame(private val wide: Boolean) : Game by Fifteen {
        override fun newUi(state: Game.State, platformSeed: ByteArray): Game.Ui = TestUi(wide)

        override fun computeSize(params: Game.Params, ui: Game.Ui, tileSize: Int): Size =
            if ((ui as TestUi).wide) {
                Size(tileSize + 30, 4 * tileSize + 10)
            } else {
                Size(4 * tileSize + 10, tileSize + 30)
            }
    }
}
