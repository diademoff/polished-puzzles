package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.PortedGames
import name.boyle.chris.sgtpuzzles.engine.spokenLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Games: the backends with a spoken board after D-01
 * wave 4.
 *
 * Each table under `testdata/spoken/` is one small preset of one game:
 * the ordered unit labels and the pointer actions, both built from game
 * state. The test compares the live board with the committed text, so
 * a changed word or a lost button is a diff in review rather than
 * silent drift. The table carries the game ID, so the board is the
 * same frozen seed-12345 board that the golden desc pins.
 *
 * The label comes from `SpokenUnit.spokenLabel`, the same function the
 * shell uses, so the table is what a screen reader reads.
 */
class BoardSemanticsGoldenTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    /** The smallest deterministic preset of each covered backend. */
    private val tables = linkedMapOf(
        "fifteen" to "4x4",
        "sixteen" to "3x3",
        "flip" to "3x3c",
        "twiddle" to "3x3n2",
        "mines" to "9x9n10",
        "mosaic" to "3x3",
        "signpost" to "4x4",
        "rect" to "7x7",
        "singles" to "5x5de",
        "unruly" to "8x8dt",
        "tents" to "8x8de",
        "samegame" to "",
        "flood" to "",
        "filling" to "9x7",
        "pegs" to "5x5random",
        "lightup" to "",
        "magnets" to "6x5de",
        "range" to "9x6",
        "towers" to "4de",
        "unequal" to "4de",
        "keen" to "4de",
        "undead" to "4x4de",
        "dominosa" to "3dt",
        // Wave 4: the own-model games and the grid games it completed.
        "loopy" to "3x2t13dh",
        "slant" to "5x5de",
        "pearl" to "6x6de",
        "tracks" to "",
        "bridges" to "7x7i30e10m2d0",
        "palisade" to "5x5n5",
        "guess" to "c6p4g10Bm",
        "map" to "20x15n30de",
        "blackbox" to "w5h5m3M3",
        "untangle" to "6",
        "net" to "5x5",
        "netslide" to "3x3",
        "inertia" to "10x8",
        "galaxies" to "7x7dn",
        "pattern" to "10x10",
        // Wave 5: the last two backends.
        "cube" to "c4x4",
        "solo" to "3x3",
    )

    @Test
    fun `the live board matches the committed spoken table`() {
        for ((backend, params) in tables) {
            val game = requireNotNull(PortedGames.byBackend(backend)) {
                "$backend is not a ported game"
            }
            val entry = goldenEntry(game, params)
            val expected = File(testdata, "spoken/$backend.txt").readText()
            assertEquals(
                "$backend '$params' drifted from testdata/spoken/$backend.txt",
                expected,
                dump(game, entry.first, entry.second, entry.third),
            )
        }
    }

    @Test
    fun `there is one table per covered backend and no stale table`() {
        val files = File(testdata, "spoken").listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
        assertEquals("testdata/spoken holds one table per covered backend", tables.keys, files)
    }

    /**
     * The spoken table of one game: the board shape and summary, then
     * every unit in board order with its pointer buttons.
     */
    private fun dump(game: Game, params: String, id: String, seed: String): String {
        val midend = Midend.fromGameId(game, id)
        val board = game.spokenBoard(midend.state, midend.ui, TILE)
        return buildString {
            appendLine("# D-01 spoken table. game=${game.backendName} params=$params seed=$seed")
            appendLine("# id=$id")
            appendLine("rows=${board.rows}")
            appendLine("columns=${board.columns}")
            appendLine("summary=${board.summary}")
            for (unit in board.units) {
                append(unit.spokenLabel())
                if (unit.actions.isNotEmpty()) {
                    append(" | ")
                    append(
                        unit.actions.joinToString(", ") {
                            "${it.label} (${buttonName(it.button)})"
                        },
                    )
                }
                appendLine()
            }
        }
    }

    /** The params, ID, and seed of the golden entry with these params. */
    private fun goldenEntry(game: Game, params: String): Triple<String, String, String> {
        val file = File(testdata, "golden/${game.backendName}/desc.json")
        val golden = Json.parse(file.readText()) as JsonValue.Obj
        val seed = golden.string("seed")
        val entries = (golden.field("entries") as JsonValue.Arr).items
        for (item in entries) {
            val entry = item as JsonValue.Obj
            if (entry.string("params") == params) {
                return Triple(params, entry.string("id"), seed)
            }
        }
        throw AssertionError("${game.backendName} has no golden entry for '$params'")
    }

    private fun buttonName(button: Int): String = when (button) {
        Buttons.LEFT_BUTTON -> "left"
        Buttons.RIGHT_BUTTON -> "right"
        Buttons.MIDDLE_BUTTON -> "middle"
        else -> button.toString()
    }

    private companion object {
        const val TILE = 32
    }
}
