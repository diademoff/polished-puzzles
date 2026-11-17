package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: map.
 *
 * The seven frozen initial saves round-trip byte for byte in both
 * directions. Map writes `AUXINFO` (the full colouring), no `UI`, no
 * `TIME`, and no `PRIVDESC`, and it keeps the `PARAMS`/`CPARAMS` split:
 * the long-term copy is always Normal because it is rebuilt from the
 * short form.
 */
class MapSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test(timeout = 120_000)
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Map.name }
                .sorted()
                .toList()
            assertEquals("six presets plus the default", 7, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Map, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), "")

                val save = SgtpSave.parse(text)
                assertTrue("${file.name} AUXINFO", save.value("AUXINFO")!!.isNotEmpty())
                assertNull("${file.name} PRIVDESC", save.value("PRIVDESC"))
                assertNull("${file.name} UI", save.value("UI"))
                assertNull("${file.name} TIME", save.value("TIME"))
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "AUXINFO", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }

            // The split is part of the contract.
            val easy = SgtpSave.parse(
                File(testdata, "saves/initial/map/20x15n30de.sgtp").readText(),
            )
            assertEquals("20x15n30dn", easy.value("PARAMS"))
            assertEquals("20x15n30de", easy.value("CPARAMS"))
            assertTrue(Midend.fromSave(Map, easy).id().startsWith("20x15n30:"))
            // The default save is byte-identical to the Normal save.
            assertEquals(
                File(testdata, "saves/initial/map/20x15n30dn.sgtp").readText(),
                File(testdata, "saves/initial/map/default.sgtp").readText(),
            )
        }

    @Test(timeout = 600_000)
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Map.presets().map { it.params } + "") {
                for (seed in seeds) {
                    val midend = Midend.newGame(Map, params, seed)
                    val text = midend.serialise().serialise()
                    val loaded = oracle.id(text)
                    assertEquals("$params/$seed id", midend.id(), loaded.id)
                    assertEquals("$params/$seed canonical text", text, loaded.state)
                    assertEquals("$params/$seed status", midend.status(), loaded.status)
                    assertFalse("$params/$seed starts with no undo", loaded.canUndo)
                    assertFalse("$params/$seed starts with no redo", loaded.canRedo)
                    assertEquals(
                        "$params/$seed AUXINFO",
                        SgtpSave.parse(text).value("AUXINFO"),
                        SgtpSave.parse(loaded.state).value("AUXINFO"),
                    )
                }
            }
        }

    @Test(timeout = 300_000)
    fun `a played history round-trips in both directions`() =
        OracleClient().use { oracle ->
            val start = oracle.newGame("map", "5x2n5", "12345").state
            // The hand-written boards have a known unique solution.
            val save = handSave("fh,0123a")
            val dragPoints = listOf(
                OracleClient.Point(30, 30, Buttons.LEFT_BUTTON),
                OracleClient.Point(110, 30, Buttons.LEFT_RELEASE),
            )
            val dragged = oracle.pointsSized(save, dragPoints, width = 141, height = 81)
            val pencilPoints = listOf(
                OracleClient.Point(30, 30, Buttons.RIGHT_BUTTON),
                OracleClient.Point(110, 30, Buttons.RIGHT_RELEASE),
            )
            val pencilled = oracle.pointsSized(save, pencilPoints, width = 141, height = 81)

            val history = listOf(
                save,
                dragged.state.state,
                pencilled.state.state,
                oracle.solve(handSave("fh,01010")).state,
            )
            for (text in history) {
                val midend = Midend.fromSave(Map, SgtpSave.parse(text))
                assertEquals("round trip", text, midend.serialise().serialise())
                assertEquals("oracle", oracle.id(text).state, midend.serialise().serialise())
            }

            // A restart is a Kotlin-only history shape: the oracle has no
            // restart key. The oracle must still load the save.
            val restarted = Midend.fromSave(Map, SgtpSave.parse(save))
            restarted.processKey(30, 30, Buttons.LEFT_BUTTON, 20)
            restarted.processKey(110, 30, Buttons.LEFT_RELEASE, 20)
            assertTrue(restarted.restart())
            val restartText = restarted.serialise().serialise()
            assertEquals(3, restarted.stateCount)
            assertEquals("RESTART", restartText.lines().last { it.startsWith("RESTART") }.substringBefore(' '))
            val loadedRestart = oracle.id(restartText)
            assertEquals(restarted.id(), loadedRestart.id)
            assertEquals(restartText, loadedRestart.state)

            // An empty MOVE and an S MOVE are accepted by both.
            val emptyMove = handSave("fh,0123a")
                .replace("NSTATES :1:1", "NSTATES :1:2")
                .replace("STATEPOS:1:1", "STATEPOS:1:2") + "MOVE    :0:\n"
            val localEmpty = Midend.fromSave(Map, SgtpSave.parse(emptyMove))
            assertEquals(2, localEmpty.stateCount)
            assertEquals(oracle.id(emptyMove).state, localEmpty.serialise().serialise())

            val sMove = handSave("fh,01010")
                .replace("NSTATES :1:1", "NSTATES :1:2")
                .replace("STATEPOS:1:1", "STATEPOS:1:2") + "MOVE    :1:S\n"
            val localS = Midend.fromSave(Map, SgtpSave.parse(sMove))
            assertTrue((localS.state as Map.State).cheated)
            assertTrue((localS.state as Map.State).completed)
            assertEquals(oracle.id(sMove).state, localS.serialise().serialise())

            // `start` is a normal generated save and round-trips too.
            assertEquals(
                start,
                Midend.fromSave(Map, SgtpSave.parse(start)).serialise().serialise(),
            )
        }

    @Test(timeout = 300_000)
    fun `an aux-free save loads and solves from the clues`() =
        OracleClient().use { oracle ->
            val withAux = oracle.newGame("map", "20x15n30dn", "12345").state
            val withoutAux = withAux.lineSequence()
                .filterNot { it.startsWith("AUXINFO") }
                .joinToString("\n", postfix = "\n")
            val expected = oracle.solve(withoutAux)
            val move = SgtpSave.parse(expected.state).value("SOLVE")!!
            assertNull(SgtpSave.parse(withoutAux).value("AUXINFO"))

            val midend = Midend.fromSave(Map, SgtpSave.parse(withoutAux))
            assertNull(midend.auxInfo)
            assertEquals(move, Map.solve(midend.state, null))
            assertTrue(midend.solve())
            assertEquals(expected.state, midend.serialise().serialise())

            // A hand-written aux round-trips, and the raw bytes match the
            // oracle's encoding of the same text.
            val midendWithAux = Midend.fromSave(Map, SgtpSave.parse(withAux))
            assertEquals(
                SgtpSave.parse(withAux).value("AUXINFO"),
                midendWithAux.serialise().value("AUXINFO"),
            )
            assertEquals(withAux, midendWithAux.serialise().serialise())
        }

    @Test(timeout = 120_000)
    fun `an empty seed stays an empty SEED record`() {
        val midend = Midend.newGame(Map, "", "")
        val save = midend.serialise()
        assertEquals("", save.value("SEED"))
        assertNull(save.value("HEXSEED"))
        assertTrue(save.serialise().contains("SEED    :0:\n"))
        assertEquals(save, Midend.fromSave(Map, SgtpSave.parse(save.serialise())).serialise())
    }

    private fun handSave(desc: String, params: String = "5x2n5dn"): String =
        "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :3:Map\n" +
            "PARAMS  :${params.length}:$params\n" +
            "CPARAMS :${params.length}:$params\n" +
            "SEED    :5:12345\n" +
            "DESC    :${desc.length}:$desc\n" +
            "NSTATES :1:1\n" +
            "STATEPOS:1:1\n"
}
