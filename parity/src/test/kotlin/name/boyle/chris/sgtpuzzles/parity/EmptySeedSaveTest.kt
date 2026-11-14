package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict for the save text. Game: fifteen and flood.
 *
 * A seed is an arbitrary byte string, and the empty string is a legal
 * one: games that are opened without a seed use it. C scans the seed for
 * a non-printable character and writes `SEED` when it finds none, so the
 * empty seed is a zero-length `SEED` record. A Kotlin save must be
 * byte-identical to the C save and must load in C.
 */
class EmptySeedSaveTest {

    @Test
    fun `an empty seed is a zero-length SEED record on both sides`() =
        OracleClient().use { oracle ->
            for ((backend, game, paramsList) in cases) {
                for (params in paramsList) {
                    val expected = oracle.newGame(backend, params, "")
                    val midend = Midend.newGame(game, params, "")
                    val text = midend.serialise().serialise()

                    assertTrue(
                        "$backend '$params' must write SEED: $text",
                        text.contains("SEED    :0:\n"),
                    )
                    assertFalse(
                        "$backend '$params' must not write HEXSEED: $text",
                        text.contains("HEXSEED"),
                    )

                    assertEquals(
                        "$backend '$params' save text",
                        expected.state,
                        text,
                    )
                    assertEquals("$backend '$params' id", expected.id, midend.id())

                    // The parser keeps the zero-length value and the
                    // writer reproduces it byte for byte.
                    val parsed = SgtpSave.parse(text)
                    assertEquals("zero-length SEED", "", parsed.value("SEED"))
                    assertEquals("no HEXSEED", null, parsed.value("HEXSEED"))
                    assertEquals("canonical text", text, parsed.serialise())

                    // C reads the Kotlin bytes.
                    val loaded = oracle.id(text)
                    assertEquals("the oracle reads the Kotlin save '$params'", expected.id, loaded.id)
                    assertEquals("the oracle rewrites the Kotlin save", text, loaded.state)
                }
            }
        }

    private val cases: List<Triple<String, Game, List<String>>> = listOf(
        Triple("fifteen", Fifteen, listOf("4x4", "5x5")),
        Triple("flood", Flood, listOf("4x4c3m0", "12x12c6m5")),
    )
}
