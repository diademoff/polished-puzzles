package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. `set_prefs` on a live UI.
 *
 * One test per game with a non-NULL `get_prefs`. Each text is parsed
 * with the game's own schema, so a wrong keyword or a missing field
 * fails here as well as in the parity suite. The expected field values
 * come from the C `set_prefs`, not from the port.
 */
class PrefsApplicationTest {

    private fun prefs(game: Game, text: String): Prefs =
        Prefs.parse(game.prefs, text)

    @Test
    fun `fifteen applies the arrow sense`() {
        val ui = Fifteen.Ui()
        Fifteen.setPrefs(ui, prefs(Fifteen, "arrow-semantics=gap\n"))
        assertTrue(ui.invertCursor)
    }

    @Test
    fun `slant applies the mouse order and the fade`() {
        val ui = Slant.Ui()
        Slant.setPrefs(
            ui,
            prefs(Slant, "left-button=/\nfade-grounded=true\n"),
        )
        assertTrue(ui.swapButtons)
        assertTrue(ui.fadeGrounded)
    }

    @Test
    fun `bridges applies the hint flag`() {
        val ui = Bridges.Ui()
        Bridges.setPrefs(ui, prefs(Bridges, "show-hints=true\n"))
        assertTrue(ui.showHints)
    }

    @Test
    fun `guess applies the label flag`() {
        val ui = Guess.Ui(Guess.defaultParams() as Guess.Params)
        Guess.setPrefs(ui, prefs(Guess, "show-labels=true\n"))
        assertTrue(ui.showLabels)
    }

    @Test
    fun `keen applies the cursor keep flag`() {
        val ui = Keen.Ui()
        Keen.setPrefs(ui, prefs(Keen, "pencil-keep-highlight=false\n"))
        assertFalse(ui.pencilKeepHighlight)
    }

    @Test
    fun `lightup applies the lit blob flag`() {
        val ui = Lightup.Ui()
        Lightup.setPrefs(ui, prefs(Lightup, "show-lit-blobs=false\n"))
        assertFalse(ui.drawBlobsWhenLit)
    }

    @Test
    fun `loopy applies the faint lines and the auto follow mode`() {
        val ui = Loopy.Ui()
        Loopy.setPrefs(
            ui,
            prefs(Loopy, "draw-faint-lines=false\nauto-follow=adaptive\n"),
        )
        assertFalse(ui.drawFaintLines)
        assertEquals(Loopy.AutoFollow.ADAPTIVE, ui.autoFollow)
    }

    @Test
    fun `map applies the flash, label, and stipple choices`() {
        val ui = Map.Ui()
        Map.setPrefs(
            ui,
            prefs(
                Map,
                "flash-type=all-white\nshow-labels=colours\nstipple-style=large\n",
            ),
        )
        assertEquals(2, ui.flashType)
        assertEquals(2, ui.showLabels)
        assertTrue(ui.largeStipples)
    }

    @Test
    fun `net applies the unlocked loop flag`() {
        val ui = Net.Ui()
        Net.setPrefs(ui, prefs(Net, "unlocked-loops=false\n"))
        assertFalse(ui.unlockedLoops)
    }

    @Test
    fun `palisade applies the cursor mode and the clear flag`() {
        val ui = Palisade.Ui()
        Palisade.setPrefs(
            ui,
            prefs(
                Palisade,
                "cursor-mode=full\nclear-complete-regions=true\n",
            ),
        )
        assertTrue(ui.legacyCursor)
        assertTrue(ui.clearCompleteRegions)
    }

    @Test
    fun `pearl applies the appearance`() {
        val ui = Pearl.Ui(64)
        Pearl.setPrefs(ui, prefs(Pearl, "appearance=loopy\n"))
        assertEquals(Pearl.Appearance.LOOPY, ui.appearance)
    }

    @Test
    fun `signpost applies the gear mode`() {
        val ui = Signpost.Ui()
        Signpost.setPrefs(ui, prefs(Signpost, "flash-type=gears\n"))
        assertTrue(ui.gearMode)
    }

    @Test
    fun `singles applies the black number flag`() {
        val ui = Singles.Ui()
        Singles.setPrefs(ui, prefs(Singles, "show-black-nums=true\n"))
        assertTrue(ui.showBlackNums)
    }

    @Test
    fun `solo applies the cursor keep flag`() {
        val ui = Solo.Ui()
        Solo.setPrefs(ui, prefs(Solo, "pencil-keep-highlight=false\n"))
        assertFalse(ui.pencilKeepHighlight)
    }

    @Test
    fun `towers applies the cursor keep and the 2D appearance`() {
        val ui = Towers.Ui()
        Towers.setPrefs(
            ui,
            prefs(Towers, "pencil-keep-highlight=false\nappearance=2d\n"),
        )
        assertFalse(ui.pencilKeepHighlight)
        assertFalse(ui.threeD)
    }

    @Test
    fun `undead applies the cursor keep and the letter mode`() {
        val ui = Undead.Ui()
        Undead.setPrefs(
            ui,
            prefs(Undead, "pencil-keep-highlight=false\nmonsters=letters\n"),
        )
        assertFalse(ui.pencilKeepHighlight)
        assertTrue(ui.ascii)
    }

    @Test
    fun `unequal applies the cursor keep flag`() {
        val ui = Unequal.Ui()
        Unequal.setPrefs(ui, prefs(Unequal, "pencil-keep-highlight=false\n"))
        assertFalse(ui.pencilKeepHighlight)
    }

    @Test
    fun `untangle applies the snap, crossed edge, and vertex choices`() {
        val ui = Untangle.Ui()
        Untangle.setPrefs(
            ui,
            prefs(
                Untangle,
                "snap-to-grid=true\nshow-crossed-edges=true\nvertex-style=number\n",
            ),
        )
        assertTrue(ui.snapToGrid)
        assertTrue(ui.showCrossedEdges)
        assertTrue(ui.vertexNumbers)
    }
}
