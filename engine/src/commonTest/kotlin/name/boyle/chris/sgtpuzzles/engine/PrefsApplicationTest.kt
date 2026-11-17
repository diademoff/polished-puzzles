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
 * come from the C `set_prefs`, not from the port.
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
        val expectedDefault: Any,
        val expected: Any,
        val read: (Game.Ui) -> Any,
    )

    private val cases = listOf(
        Case(
            Fifteen,
            { Fifteen.Ui() },
            "arrow-semantics=gap\n",
            false,
            true,
            { (it as Fifteen.Ui).invertCursor },
        ),
        Case(
            Slant,
            { Slant.Ui() },
            "left-button=/\nfade-grounded=true\n",
            false,
            true,
            { (it as Slant.Ui).swapButtons },
        ),
        Case(
            Bridges,
            { Bridges.Ui() },
            "show-hints=true\n",
            false,
            true,
            { (it as Bridges.Ui).showHints },
        ),
        Case(
            Guess,
            { Guess.Ui(Guess.defaultParams() as Guess.Params) },
            "show-labels=true\n",
            false,
            true,
            { (it as Guess.Ui).showLabels },
        ),
        Case(
            Keen,
            { Keen.Ui() },
            "pencil-keep-highlight=false\n",
            true,
            false,
            { (it as Keen.Ui).pencilKeepHighlight },
        ),
        Case(
            Lightup,
            { Lightup.Ui() },
            "show-lit-blobs=false\n",
            true,
            false,
            { (it as Lightup.Ui).drawBlobsWhenLit },
        ),
        Case(
            Loopy,
            { Loopy.Ui() },
            "draw-faint-lines=false\nauto-follow=adaptive\n",
            Loopy.AutoFollow.OFF,
            Loopy.AutoFollow.ADAPTIVE,
            { (it as Loopy.Ui).autoFollow },
        ),
        Case(
            Map,
            { Map.Ui() },
            "flash-type=all-white\n",
            0,
            2,
            { (it as Map.Ui).flashType },
        ),
        Case(
            Net,
            { Net.Ui() },
            "unlocked-loops=false\n",
            true,
            false,
            { (it as Net.Ui).unlockedLoops },
        ),
        Case(
            Palisade,
            { Palisade.Ui() },
            "cursor-mode=full\n",
            false,
            true,
            { (it as Palisade.Ui).legacyCursor },
        ),
        Case(
            Pearl,
            { Pearl.Ui(64) },
            "appearance=loopy\n",
            Pearl.Appearance.TRADITIONAL,
            Pearl.Appearance.LOOPY,
            { (it as Pearl.Ui).appearance },
        ),
        Case(
            Range,
            { Range.Ui() },
            "left-mouse-button=dot\n",
            false,
            true,
            { (it as Range.Ui).swapButtons },
        ),
        Case(
            Signpost,
            { Signpost.Ui() },
            "flash-type=gears\n",
            false,
            true,
            { (it as Signpost.Ui).gearMode },
        ),
        Case(
            Singles,
            { Singles.Ui() },
            "show-black-nums=true\n",
            false,
            true,
            { (it as Singles.Ui).showBlackNums },
        ),
        Case(
            Solo,
            { Solo.Ui() },
            "pencil-keep-highlight=false\n",
            true,
            false,
            { (it as Solo.Ui).pencilKeepHighlight },
        ),
        Case(
            Towers,
            { Towers.Ui() },
            "appearance=2d\n",
            true,
            false,
            { (it as Towers.Ui).threeD },
        ),
        Case(
            Undead,
            { Undead.Ui() },
            "monsters=letters\n",
            false,
            true,
            { (it as Undead.Ui).ascii },
        ),
        Case(
            Unequal,
            { Unequal.Ui() },
            "pencil-keep-highlight=false\n",
            true,
            false,
            { (it as Unequal.Ui).pencilKeepHighlight },
        ),
        Case(
            Untangle,
            { Untangle.Ui() },
            "snap-to-grid=true\n",
            false,
            true,
            { (it as Untangle.Ui).snapToGrid },
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
