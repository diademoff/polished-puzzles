package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * dominosa.
 *
 * The thirteen frozen initial saves round-trip byte for byte in both
 * directions. Dominosa writes `AUXINFO` and has no `UI`, no `TIME`, and
 * no `PRIVDESC`; the parser rebuilds completion from the move history.
 */
class DominosaSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Dominosa.name }
                .sorted()
                .toList()
            assertEquals("twelve presets plus the default", 13, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Dominosa, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals(
                    "${file.name} text",
                    oracle.text(text),
                    Dominosa.textFormat(midend.state),
                )

                val save = SgtpSave.parse(text)
                assertNotNull("${file.name} AUXINFO", save.value("AUXINFO"))
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
                // The layout text is `wh` characters, so AUXINFO is
                // `2*wh` hex digits.
                val wh = (midend.state as Dominosa.State).wh
                assertEquals("${file.name} aux length", wh * 2, save.value("AUXINFO")!!.length)
                assertEquals(wh, midend.auxInfo!!.length)
            }

            // The default and 6db are the same game.
            val default = File(testdata, "saves/initial/dominosa/default.sgtp").readText()
            val sixDb = File(testdata, "saves/initial/dominosa/6db.sgtp").readText()
            assertEquals(default, sixDb)
        }

    @Test(timeout = 300_000)
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Dominosa.presets().map { it.params } +
                listOf("1dt", "1da", "2dh", "2da")
            ) {
                for (seed in seeds) {
                    val midend = Midend.newGame(Dominosa, params, seed)
                    val text = midend.serialise().serialise()
                    val loaded = oracle.id(text)
                    assertEquals("$params/$seed id", midend.id(), loaded.id)
                    assertEquals("$params/$seed canonical text", text, loaded.state)
                    assertEquals("$params/$seed status", midend.status(), loaded.status)
                    assertFalse("$params/$seed starts with no undo", loaded.canUndo)
                    assertFalse("$params/$seed starts with no redo", loaded.canRedo)
                }
            }
        }

    @Test(timeout = 120_000)
    fun `a real click and a real edge round-trip in both directions`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            click(midend, 0, 0, place = true)
            assertEquals("D0,1", lastMove(midend))
            assertEquals(2, midend.stateCount)
            click(midend, 0, 1, place = false)
            assertEquals("E5,6", lastMove(midend))
            assertEquals(3, midend.stateCount)
            assertRoundTrip(oracle, midend)
        }

    @Test(timeout = 120_000)
    fun `an undone position with a live redo branch round-trips`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Dominosa, "3dt", SEED)
            click(midend, 0, 0, place = true)
            click(midend, 0, 1, place = false)
            assertTrue(midend.undo())
            assertTrue(midend.canRedo)
            val text = midend.serialise().serialise()
            assertTrue(SgtpSave.parse(text).value("NSTATES")!!.toInt() >= 2)
            val loaded = oracle.id(text)
            assertEquals(midend.id(), loaded.id)
            assertEquals(text, loaded.state)
            assertEquals(midend.status(), loaded.status)
            assertTrue(loaded.canUndo)
            assertTrue(loaded.canRedo)
            assertRoundTrip(oracle, midend)
        }

    @Test(timeout = 300_000)
    fun `a solve a restart and an S move round-trip`() = OracleClient().use { oracle ->
        val solved = Midend.newGame(Dominosa, "6db", SEED)
        assertTrue(solved.solve())
        assertEquals(1, solved.status())
        assertTrue(
            SgtpSave.parse(solved.serialise().serialise()).value("SOLVE")!!.startsWith("S"),
        )
        assertRoundTrip(oracle, solved)

        val restarted = Midend.newGame(Dominosa, "3dt", SEED)
        click(restarted, 0, 0, place = true)
        assertTrue(restarted.restart())
        assertEquals(0, restarted.status())
        val restartSave = restarted.serialise()
        assertEquals(restartSave.value("DESC"), restartSave.value("RESTART"))
        assertTrue(restarted.stateCount >= 2)
        assertRoundTrip(oracle, restarted)

        // An `S` move is always a MOVE record, and the cheated flag is
        // rebuilt from it on load.
        val base = Midend.newGame(Dominosa, "3dt", SEED).serialise().serialise()
        val cheatedSave = withMove(base, "S;D0,1")
        val cheated = Midend.fromSave(Dominosa, SgtpSave.parse(cheatedSave))
        assertTrue((cheated.state as Dominosa.State).cheated)
        assertEquals(cheatedSave, cheated.serialise().serialise())
        val loaded = oracle.id(cheatedSave)
        assertEquals(0, loaded.status)
        assertEquals(loaded.state, cheated.serialise().serialise())
        assertRoundTrip(oracle, cheated)
    }

    @Test(timeout = 120_000)
    fun `the AUXINFO round-trip keeps the auxiliary answer`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Dominosa, "6db", SEED)
        val text = midend.serialise().serialise()
        val save = SgtpSave.parse(text)
        val loaded = Midend.fromSave(Dominosa, save)
        assertEquals(midend.auxInfo, loaded.auxInfo)
        assertEquals(56, loaded.auxInfo!!.length)
        assertEquals(
            "TTTLRTTTBBBLRBBBLRLRLRLRLRTLRLRTTTBTTTTBBBTBBBBTLRBLRLRB",
            loaded.auxInfo,
        )
        assertEquals(
            text,
            loaded.serialise().serialise(),
        )
        assertEquals(
            Dominosa.solve(loaded.state, loaded.auxInfo),
            SgtpSave.parse(oracle.solve(text).state).value("SOLVE"),
        )
    }

    @Test(timeout = 120_000)
    fun `a hand-written short AUXINFO is defined as dot beyond the text`() =
        OracleClient().use { oracle ->
            val base = Midend.newGame(Dominosa, "3dt", SEED).serialise().serialise()
            for (aux in listOf("00", "41", "4c", "54", "ff")) {
                val text = replaceAux(base, aux)
                val expected = SgtpSave.parse(oracle.solve(text).state).value("SOLVE")
                assertEquals("oracle solve for aux $aux", "S", expected)

                val loaded = Midend.fromSave(Dominosa, SgtpSave.parse(text))
                assertEquals("aux text for $aux", 1, loaded.auxInfo!!.length)
                assertEquals(
                    "Kotlin solve for aux $aux",
                    expected,
                    Dominosa.solve(loaded.state, loaded.auxInfo),
                )
            }
        }

    @Test
    fun `a bad description and a bad parameter are rejected`() = OracleClient().use { oracle ->
        val good = Midend.newGame(Dominosa, "3dt", SEED).serialise().serialise()
        val badDesc = good.replace(
            "DESC    :20:01012311032222033310",
            "DESC    :20:01012311032222033311",
        )
        val descError = try {
            oracle.id(badDesc)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Game description in save file is invalid", descError)
        val kotlinDescError = try {
            Midend.fromSave(Dominosa, SgtpSave.parse(badDesc))
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        assertEquals(
            "game description: Incorrect number balance in game description",
            kotlinDescError,
        )

        val badParams = good.replace("PARAMS  :3:3db", "PARAMS  :5:46340")
        val paramsError = try {
            oracle.id(badParams)
            null
        } catch (e: OracleException) {
            e.message
        }
        assertEquals("Long-term parameters in save file are invalid", paramsError)
        val kotlinParamsError = try {
            Midend.fromSave(Dominosa, SgtpSave.parse(badParams))
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        assertEquals(
            "long-term parameters: Maximum face number must not be unreasonably large",
            kotlinParamsError,
        )
    }

    private fun assertRoundTrip(oracle: OracleClient, midend: Midend) {
        val text = midend.serialise().serialise()
        val loaded = oracle.id(text)
        assertEquals("id", midend.id(), loaded.id)
        assertEquals("canonical text", text, loaded.state)
        assertEquals("status", midend.status(), loaded.status)
        assertEquals("can undo", midend.canUndo, loaded.canUndo)
        assertEquals("can redo", midend.canRedo, loaded.canRedo)
        assertEquals("text", oracle.text(text), Dominosa.textFormat(midend.state))
        val reloaded = Midend.fromSave(Dominosa, SgtpSave.parse(loaded.state))
        assertEquals(text, reloaded.serialise().serialise())
        assertEquals(midend.id(), reloaded.id())
    }

    private fun replaceAux(save: String, aux: String): String =
        save.split("\n").joinToString("\n") { line ->
            if (line.startsWith("AUXINFO")) "AUXINFO :2:$aux" else line
        }

    private fun withMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private fun lastMove(midend: Midend): String =
        SgtpSave.parse(midend.serialise().serialise()).values("MOVE").lastOrNull() ?: ""

    /**
     * One real click on a half of a cell at the preferred tile 32. The
     * border is 24 and the tile is 32, so the right half is at `x + 24`
     * plus a quarter tile, and the bottom half at `y + 24` plus a
     * quarter tile.
     */
    private fun click(midend: Midend, x: Int, y: Int, place: Boolean) {
        val tile = 32
        val border = tile * 3 / 4
        val px = border + x * tile + tile / 2 + tile / 4
        val py = border + y * tile + tile / 2
        midend.processKey(
            px,
            py,
            if (place) Buttons.LEFT_BUTTON else Buttons.RIGHT_BUTTON,
            tile,
        )
        midend.processKey(
            px,
            py,
            if (place) Buttons.LEFT_RELEASE else Buttons.RIGHT_RELEASE,
            tile,
        )
    }

    private companion object {
        const val SEED = "12345"
    }
}
