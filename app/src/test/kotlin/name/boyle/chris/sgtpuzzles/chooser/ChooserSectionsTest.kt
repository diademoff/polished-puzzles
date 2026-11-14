package name.boyle.chris.sgtpuzzles.chooser

import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.NET
import org.junit.Assert.assertEquals
import org.junit.Test

class ChooserSectionsTest {

    private val games = listOf(GUESS, KEEN, NET)

    @Test
    fun `starred games come first and keep their order`() {
        val sections = chooserSections(games, setOf(NET, GUESS))

        assertEquals(
            listOf(
                ChooserEntry.Section(R.string.games_starred),
                ChooserEntry.Game(GUESS),
                ChooserEntry.Game(NET),
                ChooserEntry.Section(R.string.games_others),
                ChooserEntry.Game(KEEN),
            ),
            sections,
        )
    }

    @Test
    fun `a single section is used when nothing is starred`() {
        val sections = chooserSections(games, emptySet())

        assertEquals(
            listOf(
                ChooserEntry.Section(R.string.games_others_none_starred),
                ChooserEntry.Game(GUESS),
                ChooserEntry.Game(KEEN),
                ChooserEntry.Game(NET),
            ),
            sections,
        )
    }

    @Test
    fun `every game appears exactly once`() {
        val sections = chooserSections(games, setOf(KEEN))

        val shown = sections.filterIsInstance<ChooserEntry.Game>().map { it.game }
        assertEquals(games.size, shown.size)
        assertEquals(games.toSet(), shown.toSet())
    }
}
