package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Level: semantic. `set_prefs` on a live UI.
 *
 * One case per game with a non-NULL C `get_prefs`. Each text is parsed
 * with the game's own schema, so a wrong keyword or a missing field
 * fails here as well as in the parity suite. The expected field values
 * come from the C `set_prefs`, not from the port; a game that sets more
 * than one field checks every field in one case.
 *
 * The matrix is self-checking: [cases] must name exactly the games that
 * [Game.declaresPrefs] marks as having C preferences, which
 * `PrefsSchemaParityTest` compares with the oracle. A ported game with
 * C preferences cannot be added without a case here.
 */
class PrefsApplicationTest {

    private class Case(
        val game: Game,
        val newUi: () -> Game.Ui,
        val text: String,
        val expectedDefault: List<Any>,
        val expected: List<Any>,
        val read: (Game.Ui) -> List<Any>,
    )

    private val cases = listOf(
        Case(
            Fifteen,
            { Fifteen.Ui() },
            "arrow-semantics=gap\n",
            listOf(false),
            listOf(true),
            { listOf((it as Fifteen.Ui).invertCursor) },
        ),
        Case(
            Slant,
            { Slant.Ui() },
            "left-button=/\nfade-grounded=true\n",
            listOf(false, false),
            listOf(true, true),
            {
                val ui = it as Slant.Ui
                listOf(ui.swapButtons, ui.fadeGrounded)
            },
        ),
        Case(
            Bridges,
            { Bridges.Ui() },
            "show-hints=true\n",
            listOf(false),
            listOf(true),
            { listOf((it as Bridges.Ui).showHints) },
        ),
        Case(
            Guess,
            { Guess.Ui(Guess.defaultParams() as Guess.Params) },
            "show-labels=true\n",
            listOf(false),
            listOf(true),
            { listOf((it as Guess.Ui).showLabels) },
        ),
        Case(
            Keen,
            { Keen.Ui() },
            "pencil-keep-highlight=false\n",
            listOf(true),
            listOf(false),
            { listOf((it as Keen.Ui).pencilKeepHighlight) },
        ),
        Case(
            Lightup,
            { Lightup.Ui() },
            "show-lit-blobs=false\n",
            listOf(true),
            listOf(false),
            { listOf((it as Lightup.Ui).drawBlobsWhenLit) },
        ),
        Case(
            Loopy,
            { Loopy.Ui() },
            "draw-faint-lines=false\nauto-follow=adaptive\n",
            listOf(true, Loopy.AutoFollow.OFF),
            listOf(false, Loopy.AutoFollow.ADAPTIVE),
            {
                val ui = it as Loopy.Ui
                listOf(ui.drawFaintLines, ui.autoFollow)
            },
        ),
        Case(
            Map,
            { Map.Ui() },
            "flash-type=all-white\nshow-labels=colours\nstipple-style=large\n",
            listOf(0, 0, false),
            listOf(2, 2, true),
            {
                val ui = it as Map.Ui
                listOf(ui.flashType, ui.showLabels, ui.largeStipples)
            },
        ),
        Case(
            Net,
            { Net.Ui() },
            "unlocked-loops=false\n",
            listOf(true),
            listOf(false),
            { listOf((it as Net.Ui).unlockedLoops) },
        ),
        Case(
            Palisade,
            { Palisade.Ui() },
            "cursor-mode=full\nclear-complete-regions=true\n",
            listOf(false, false),
            listOf(true, true),
            {
                val ui = it as Palisade.Ui
                listOf(ui.legacyCursor, ui.clearCompleteRegions)
            },
        ),
        Case(
            Pearl,
            { Pearl.Ui(64) },
            "appearance=loopy\n",
            listOf(Pearl.Appearance.TRADITIONAL),
            listOf(Pearl.Appearance.LOOPY),
            { listOf((it as Pearl.Ui).appearance) },
        ),
        Case(
            Range,
            { Range.Ui() },
            "left-mouse-button=dot\n",
            listOf(false),
            listOf(true),
            { listOf((it as Range.Ui).swapButtons) },
        ),
        Case(
            Signpost,
            { Signpost.Ui() },
            "flash-type=gears\n",
            listOf(false),
            listOf(true),
            { listOf((it as Signpost.Ui).gearMode) },
        ),
        Case(
            Singles,
            { Singles.Ui() },
            "show-black-nums=true\n",
            listOf(false),
            listOf(true),
            { listOf((it as Singles.Ui).showBlackNums) },
        ),
        Case(
            Solo,
            { Solo.Ui() },
            "pencil-keep-highlight=false\n",
            listOf(true),
            listOf(false),
            { listOf((it as Solo.Ui).pencilKeepHighlight) },
        ),
        Case(
            Towers,
            { Towers.Ui() },
            "pencil-keep-highlight=false\nappearance=2d\n",
            listOf(true, true),
            listOf(false, false),
            {
                val ui = it as Towers.Ui
                listOf(ui.pencilKeepHighlight, ui.threeD)
            },
        ),
        Case(
            Undead,
            { Undead.Ui() },
            "pencil-keep-highlight=false\nmonsters=letters\n",
            listOf(true, false),
            listOf(false, true),
            {
                val ui = it as Undead.Ui
                listOf(ui.pencilKeepHighlight, ui.ascii)
            },
        ),
        Case(
            Unequal,
            { Unequal.Ui() },
            "pencil-keep-highlight=false\n",
            listOf(true),
            listOf(false),
            { listOf((it as Unequal.Ui).pencilKeepHighlight) },
        ),
        Case(
            Untangle,
            { Untangle.Ui() },
            "snap-to-grid=true\nshow-crossed-edges=true\nvertex-style=number\n",
            listOf(false, false, false),
            listOf(true, true, true),
            {
                val ui = it as Untangle.Ui
                listOf(ui.snapToGrid, ui.showCrossedEdges, ui.vertexNumbers)
            },
        ),
    )

    @Test
    fun `every game with C prefs applies a non-default value`() {
        val declared = PortedGames.all.filter { it.declaresPrefs }.toSet()
        assertEquals(19, declared.size, "the C prefs matrix size")
        assertEquals(
            declared,
            cases.map { it.game }.toSet(),
            "every game with C prefs needs a case",
        )

        for (case in cases) {
            val ui = case.newUi()
            assertEquals(
                case.expectedDefault,
                case.read(ui),
                "${case.game.backendName} default must be the non-applied value",
            )
            case.game.setPrefs(ui, Prefs.parse(case.game.prefs, case.text))
            assertEquals(
                case.expected,
                case.read(ui),
                "${case.game.backendName} applies '${
                    case.text.trim().replace('\n', ';')
                }'",
            )
            assertNotEquals(
                case.expectedDefault,
                case.expected,
                "${case.game.backendName} must change a field",
            )
        }
    }

    @Test
    fun `the schema parses each case text`() {
        for (case in cases) {
            val prefs = Prefs.parse(case.game.prefs, case.text)
            assertTrue(
                prefs.items.isNotEmpty(),
                "${case.game.backendName} parses '${case.text.trim()}'",
            )
        }
    }

    @Test
    fun `a game without C prefs declares no schema`() {
        for (game in PortedGames.all.filter { !it.declaresPrefs }) {
            assertTrue(game.prefs.isEmpty(), "${game.backendName} has no schema")
        }
        assertFalse(Mines.declaresPrefs, "mines has a NULL C get_prefs")
    }
}
