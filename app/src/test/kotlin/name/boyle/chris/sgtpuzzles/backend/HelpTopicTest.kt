package name.boyle.chris.sgtpuzzles.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: semantic. The help topic is a public string, so it must match
 * the C engine (`docs/PARITY.md` section 1).
 *
 * `android.c` returns `thegame->htmlhelp_topic` verbatim. The field
 * order is `name, winhelp_topic, htmlhelp_topic` (`puzzles.h:713`),
 * and every `thegame` table sets the HTML topic to the backend name:
 *
 * - `fifteen.c:1165`: `"Fifteen", "games.fifteen", "fifteen"`
 * - `slant.c:2317`: `"Slant", "games.slant", "slant"`
 * - `samegame.c:1660`: `"Same Game", "games.samegame", "samegame"`
 * - `flood.c:1367`: `"Flood", "games.flood", "flood"`
 * - `sixteen.c:1188`: `"Sixteen", "games.sixteen", "sixteen"`
 * - `flip.c:1324`: `"Flip", "games.flip", "flip"`
 * - `twiddle.c:1285`: `"Twiddle", "games.twiddle", "twiddle"`
 * - `pegs.c:1370`: `"Pegs", "games.pegs", "pegs"`
 * - `guess.c:1558`: `"Guess", "games.guess", "guess"`
 * - `blackbox.c:1556`: `"Black Box", "games.blackbox", "blackbox"`
 * - `cube.c:1760`: `"Cube", "games.cube", "cube"`
 * - `filling.c:2178`: `"Filling", "games.filling", "filling"`
 * - `netslide.c:1861`: `"Netslide", "games.netslide", "netslide"`
 * - `unruly.c:2034`: `"Unruly", "games.unruly", "unruly"`
 * - `range.c:1855`: `"Range", "games.range", "range"`
 * - `lightup.c:2374`: `"Light Up", "games.lightup", "lightup"`
 * - `mosaic.c:1595`: `"Mosaic", "games.mosaic", "mosaic"`
 * - `palisade.c:1624`: `"Palisade", "games.palisade", "palisade"`
 * - `inertia.c:2222`: `"Inertia", "games.inertia", "inertia"`
 * - `tents.c:2649`: `"Tents", "games.tents", "tents"`
 * - `magnets.c:2456`: `"Magnets", "games.magnets", "magnets"`
 * - `pattern.c:2094`: `"Pattern", "games.pattern", "pattern"`
 * - `signpost.c:2348`: `"Signpost", "games.signpost", "signpost"`
 * - `undead.c:2883`: `"Undead", "games.undead", "undead"`
 * - `untangle.c:2027`: `"Untangle", "games.untangle", "untangle"`
 * - `bridges.c:3346`: `"Bridges", "games.bridges", "bridges"`
 * - `net.c:3364`: `"Net", "games.net", "net"`
 * - `mines.c:3352`: `"Mines", "games.mines", "mines"`
 * - `tracks.c:3027`: `"Train Tracks", "games.tracks", "tracks"`
 * - `dominosa.c:3463`: `"Dominosa", "games.dominosa", "dominosa"`
 * - `map.c:3390`: `"Map", "games.map", "map"`
 * - `keen.c:2575`: `"Keen", "games.keen", "keen"`
 * - `towers.c:2175`: `"Towers", "games.towers", "towers"`
 * - `unequal.c:2250`: `"Unequal", "games.unequal", "unequal"`
 * - `singles.c:1886`: `"Singles", "games.singles", "singles"`
 * - `pearl.c:2805`: `"Pearl", "games.pearl", "pearl"`
 *
 * The oracle is not available to app unit tests, so the expected value
 * is the third field of these initializers. `HelpActivity` accepts only
 * `^[a-z]+$` (`HelpActivity.kt:167`), so a `"games."` prefix would make
 * the activity close instead of opening the help page.
 */
class HelpTopicTest {

    /** The backend name and the C `htmlhelp_topic` for every Kotlin game. */
    private val expectedTopics = listOf(
        FIFTEEN to "fifteen",
        SLANT to "slant",
        SAMEGAME to "samegame",
        FLOOD to "flood",
        SIXTEEN to "sixteen",
        FLIP to "flip",
        TWIDDLE to "twiddle",
        PEGS to "pegs",
        GUESS to "guess",
        BLACKBOX to "blackbox",
        CUBE to "cube",
        FILLING to "filling",
        NETSLIDE to "netslide",
        UNRULY to "unruly",
        RANGE to "range",
        LIGHTUP to "lightup",
        MOSAIC to "mosaic",
        PALISADE to "palisade",
        PATTERN to "pattern",
        SIGNPOST to "signpost",
        INERTIA to "inertia",
        TENTS to "tents",
        MAGNETS to "magnets",
        UNDEAD to "undead",
        UNTANGLE to "untangle",
        BRIDGES to "bridges",
        NET to "net",
        MINES to "mines",
        TRACKS to "tracks",
        DOMINOSA to "dominosa",
        MAP to "map",
        KEEN to "keen",
        TOWERS to "towers",
        UNEQUAL to "unequal",
        SINGLES to "singles",
        PEARL to "pearl",
        LOOPY to "loopy",
    )

    @Test
    fun `every Kotlin game returns the C html help topic`() {
        for ((backend, topic) in expectedTopics) {
            val game = requireNotNull(KotlinGames.gameFor(backend)) {
                "no Kotlin game for $backend"
            }
            assertEquals("help topic for $backend", topic, htmlHelpTopicFor(game))
            assertEquals("help topic for $backend", backend.sourceName, htmlHelpTopicFor(game))
        }
    }

    @Test
    fun `every help topic passes the HelpActivity filter`() {
        val allowed = Regex("^[a-z]+$")
        for ((backend, _) in expectedTopics) {
            val game = requireNotNull(KotlinGames.gameFor(backend))
            val topic = htmlHelpTopicFor(game)
            assertTrue("help topic for $backend is '$topic'", allowed.matches(topic))
        }
    }

    @Test
    fun `the expected list covers every Kotlin game`() {
        val known = BackendName.all.filter { KotlinGames.gameFor(it) != null }.toSet()
        assertEquals(known, expectedTopics.map { it.first }.toSet())
    }
}
