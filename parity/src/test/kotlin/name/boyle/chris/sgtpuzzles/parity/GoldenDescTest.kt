package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Every game.
 *
 * Compares `params + seed -> DESC` from the oracle with the frozen
 * goldens in testdata/golden. A failure means one of three things:
 *
 * 1. our oracle build changed (compiler, compile definitions, source);
 * 2. upstream changed the generator;
 * 3. a golden was edited by hand.
 *
 * Find the cause. Do not regenerate the goldens to make this test pass.
 *
 * The test also pins coverage: the golden directories must be exactly
 * the games the oracle knows about, and every golden must have at least
 * one entry.
 */
class GoldenDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every golden still matches the oracle`() = OracleClient().use { oracle ->
        val games = oracle.games().sorted()
        val dirs = File(testdata, "golden").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()

        assertEquals(
            "every game needs a golden directory",
            games,
            dirs.map { it.name },
        )

        var checked = 0
        for (dir in dirs) {
            val golden = Json.parse(File(dir, "desc.json").readText()) as JsonValue.Obj
            val game = golden.string("game")
            val seed = golden.string("seed")
            assertEquals("directory name vs game field", dir.name, game)

            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertTrue("$game has no entries", entries.isNotEmpty())

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val expected = entry.string("id")
                val actual = oracle.newGame(game, params, seed).id
                assertEquals("$game params='$params' seed=$seed", expected, actual)
                checked++
            }
        }

        assertTrue("checked only $checked entries", checked >= games.size)
    }
}
