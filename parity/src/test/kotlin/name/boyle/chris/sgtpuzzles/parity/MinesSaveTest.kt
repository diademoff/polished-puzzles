package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: mines.
 *
 * The five frozen initial saves round-trip byte for byte in both
 * directions. Mines is the first real consumer of `PRIVDESC` and the
 * second of the `TIME` record, so both are pinned here: a Kotlin save
 * must load in C, a C save must load in Kotlin, and the canonical text
 * must be identical.
 */
class MinesSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private val tile = 20

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Mines.name }
                .sorted()
                .toList()
            assertEquals("four presets plus the default", 5, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Mines, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} status text",
                    oracleStatus(oracle, text),
                    midend.statusText(),
                )

                val save = SgtpSave.parse(text)
                assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertNull("${file.name} PRIVDESC", save.value("PRIVDESC"))
                assertEquals("${file.name} UI", "D0", save.value("UI"))
                assertEquals("${file.name} TIME", "0", save.value("TIME"))
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "UI", "TIME", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }
        }

    @Test
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Mines.presets().map { it.params }) {
                for (seed in seeds) {
                    val midend = Midend.newGame(Mines, params, seed)
                    val text = midend.serialise().serialise()
                    val loaded = oracle.id(text)
                    assertEquals("$params/$seed id", midend.id(), loaded.id)
                    assertEquals("$params/$seed canonical text", text, loaded.state)
                    assertEquals("$params/$seed status", midend.status(), loaded.status)
                    assertFalse("$params/$seed starts with no undo", loaded.canUndo)
                    assertFalse("$params/$seed starts with no redo", loaded.canRedo)
                    assertEquals("$params/$seed UI", "D0", SgtpSave.parse(text).value("UI"))
                    assertEquals("$params/$seed TIME", "0", SgtpSave.parse(text).value("TIME"))
                }
            }
        }

    @Test(timeout = 120_000)
    fun `a played first click round-trips in both directions`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Mines, "9x9n10", SEED)
            click(midend, 4, 4)

            val text = midend.serialise().serialise()
            val loaded = oracle.id(text)
            assertEquals("id", midend.id(), loaded.id)
            assertEquals("status", midend.status(), loaded.status)
            assertEquals("canonical text", text, loaded.state)
            assertEquals("status text", oracleStatus(oracle, text), midend.statusText())
            assertEquals("DESC", "4,4,m9eed07198e89048f2e4d0", SgtpSave.parse(text).value("DESC"))
            assertEquals(
                "PRIVDESC",
                "m9eed07198e89048f2e4d0",
                SgtpSave.parse(text).value("PRIVDESC"),
            )
            assertEquals("MOVE", "O4,4", SgtpSave.parse(text).value("MOVE"))

            // The C save loads in Kotlin and rewrites byte for byte.
            val reloaded = Midend.fromSave(Mines, SgtpSave.parse(loaded.state))
            assertEquals(text, reloaded.serialise().serialise())
            assertEquals(midend.state, reloaded.state)
            assertEquals(midend.id(), reloaded.id())
            assertTrue(reloaded.canUndo)
        }

    @Test(timeout = 120_000)
    fun `PRIVDESC rebuilds state zero and its absence opens the click`() =
        OracleClient().use { oracle ->
            val played = Midend.newGame(Mines, "9x9n10", SEED)
            click(played, 4, 4)
            val text = played.serialise().serialise()

            // With PRIVDESC, state 0 is the all-covered pending board
            // and the public description restores the start cross.
            val withPrivate = SgtpSave.parse(text)
            val reloaded = Midend.fromSave(Mines, withPrivate)
            assertTrue(reloaded.undo())
            val stateZero = reloaded.state as Mines.State
            assertTrue(stateZero.grid.all { it == -2 })
            assertEquals(4, stateZero.layout.startX)
            assertEquals(4, stateZero.layout.startY)
            assertEquals("Marked: 0 / 10", Mines.statusText(stateZero, reloaded.ui))
            assertTrue(reloaded.canRedo)

            // Without PRIVDESC, state 0 is built from the public
            // description, so the click is already open.
            val without = SgtpSave.of(withPrivate.records.filterNot { it.key == "PRIVDESC" })
            val reloadedWithout = Midend.fromSave(Mines, without)
            val opened = reloadedWithout.state as Mines.State
            assertFalse(opened.grid.all { it == -2 })
            assertEquals(-1, opened.layout.startX)
            assertEquals(played.textFormat(), reloadedWithout.textFormat())

            // The oracle accepts both forms with the same public id.
            assertEquals(played.id(), oracle.id(text).id)
            assertEquals(played.id(), oracle.id(without.serialise()).id)
        }

    @Test(timeout = 120_000)
    fun `every history shape round-trips in both directions`() =
        OracleClient().use { oracle ->
            val cases = LinkedHashMap<String, Pair<Midend, String>>()

            val opened = Midend.newGame(Mines, "9x9n10", SEED)
            click(opened, 4, 4)
            cases["open"] = opened to "O4,4"

            val flagged = Midend.newGame(Mines, "9x9n10", SEED)
            click(flagged, 4, 4)
            rightClick(flagged, 0, 0)
            cases["flag"] = flagged to "F0,0"

            val chord = Midend.newGame(Mines, "9x9n10", SEED)
            click(chord, 4, 4)
            rightClick(chord, 0, 0)
            rightClick(chord, 0, 1)
            middleClick(chord, 1, 0)
            cases["chord"] = chord to "C1,0"

            val dead = Midend.newGame(Mines, "9x9n10", SEED)
            click(dead, 4, 4)
            click(dead, 0, 0)
            assertTrue((dead.state as Mines.State).dead)
            cases["death"] = dead to "O0,0"

            val won = Midend.newGame(Mines, "4x4n1", "7")
            click(won, 0, 0)
            click(won, 0, 3)
            assertTrue((won.state as Mines.State).won)
            cases["win"] = won to "O0,3"

            for ((name, pair) in cases) {
                val (midend, lastMove) = pair
                val text = midend.serialise().serialise()
                val save = SgtpSave.parse(text)
                assertEquals("$name last move", lastMove, save.values("MOVE").last())
                // The record order after a click: DESC, PRIVDESC, UI,
                // TIME, then the history.
                val keys = save.records.map { it.key }
                if (name == "win") {
                    assertEquals("$name PARAMS", "4x4n1", save.value("PARAMS"))
                    assertEquals("$name CPARAMS", "4x4n1", save.value("CPARAMS"))
                } else {
                    assertEquals("$name PARAMS", "9x9n8", save.value("PARAMS"))
                    assertEquals("$name CPARAMS", "9x9n10", save.value("CPARAMS"))
                }
                assertTrue("$name PRIVDESC", save.value("PRIVDESC")!!.startsWith("m"))
                assertEquals(
                    "$name UI",
                    when (name) {
                        "win" -> "D0C"
                        "death" -> "D1"
                        else -> "D0"
                    },
                    save.value("UI"),
                )
                assertEquals("$name TIME", "0", save.value("TIME"))
                assertTrue(
                    "$name PRIVDESC before UI",
                    keys.indexOf("PRIVDESC") < keys.indexOf("UI"),
                )
                assertTrue(
                    "$name TIME after UI",
                    keys.indexOf("UI") < keys.indexOf("TIME"),
                )

                val expected = oracle.id(text)
                assertEquals("$name id", midend.id(), expected.id)
                assertEquals("$name status", midend.status(), expected.status)
                assertTrue("$name can undo", expected.canUndo)
                assertFalse("$name can redo", expected.canRedo)
                assertEquals("$name canonical", text, expected.state)
                assertEquals("$name text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "$name status text",
                    oracleStatus(oracle, text),
                    midend.statusText(),
                )

                val reloaded = Midend.fromSave(Mines, save)
                assertEquals("$name reload", text, reloaded.serialise().serialise())
                assertEquals("$name reload UI", save.value("UI"), reloaded.serialise().value("UI"))
                assertEquals("$name reload TIME", "0", reloaded.serialise().value("TIME"))
            }

            // The live redo branch: undo after the chord, save, and the
            // branch must stay redoable in both engines.
            val redoable = Midend.newGame(Mines, "9x9n10", SEED)
            click(redoable, 4, 4)
            rightClick(redoable, 0, 0)
            assertTrue(redoable.undo())
            val redoText = redoable.serialise().serialise()
            val redoSave = SgtpSave.parse(redoText)
            assertEquals("redo STATEPOS", "2", redoSave.value("STATEPOS"))
            assertEquals("redo NSTATES", "3", redoSave.value("NSTATES"))
            assertTrue("redo branch", oracle.id(redoText).canRedo)
            assertTrue("redo branch", Midend.fromSave(Mines, redoSave).canRedo)

            // A restart after the first click appends the public desc.
            val restarted = Midend.newGame(Mines, "9x9n10", SEED)
            click(restarted, 4, 4)
            assertTrue(restarted.restart())
            assertEquals(
                "restart record",
                "4,4,m9eed07198e89048f2e4d0",
                restarted.serialise().value("RESTART"),
            )
        }

    @Test
    fun `an undone position keeps a live redo branch`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        click(midend, 4, 4)
        assertTrue(midend.undo())

        val text = midend.serialise().serialise()
        assertEquals("1", SgtpSave.parse(text).value("STATEPOS"))
        assertEquals("2", SgtpSave.parse(text).value("NSTATES"))
        assertEquals(midend.id(), oracle.id(text).id)
        assertTrue(oracle.id(text).canRedo)

        val reloaded = Midend.fromSave(Mines, SgtpSave.parse(text))
        assertTrue(reloaded.canRedo)
        assertEquals(1, reloaded.statePos)
        assertEquals(midend.id(), reloaded.id())
        assertTrue(reloaded.redo())
        assertEquals(2, reloaded.statePos)
    }

    @Test(timeout = 120_000)
    fun `solve and restart records round-trip`() = OracleClient().use { oracle ->
        val solved = Midend.newGame(Mines, "9x9n10", SEED)
        click(solved, 4, 4)
        assertTrue(solved.solve())
        val solvedText = solved.serialise().serialise()
        assertEquals("S", SgtpSave.parse(solvedText).value("SOLVE"))
        assertEquals(solved.id(), oracle.id(solvedText).id)
        assertEquals(solved.status(), oracle.id(solvedText).status)
        assertEquals(solvedText, oracle.id(solvedText).state)
        assertEquals(
            oracleStatus(oracle, solvedText),
            solved.statusText(),
        )
        assertEquals(
            solvedText,
            Midend.fromSave(Mines, SgtpSave.parse(solvedText)).serialise().serialise(),
        )

        val restarted = Midend.newGame(Mines, "9x9n10", SEED)
        click(restarted, 4, 4)
        assertTrue(restarted.restart())
        val restartedText = restarted.serialise().serialise()
        assertEquals("RESTART", SgtpSave.parse(restartedText).records.last().key)
        assertEquals(
            "4,4,m9eed07198e89048f2e4d0",
            SgtpSave.parse(restartedText).records.last().value,
        )
        assertEquals(restarted.id(), oracle.id(restartedText).id)
        assertEquals(restartedText, oracle.id(restartedText).state)
        assertEquals(
            restartedText,
            Midend.fromSave(Mines, SgtpSave.parse(restartedText)).serialise().serialise(),
        )
    }

    @Test
    fun `a first history record of restart loads in both engines`() =
        OracleClient().use { oracle ->
            val text = historySave(
                desc = "4,4,m9eed07198e89048f2e4d0",
                privDesc = "m9eed07198e89048f2e4d0",
                history = listOf("RESTART" to "4,4,m9eed07198e89048f2e4d0"),
                statePos = 2,
            )
            val midend = Midend.fromSave(Mines, SgtpSave.parse(text))
            assertEquals(midend.id(), oracle.id(text).id)
            assertEquals(midend.status(), oracle.id(text).status)
            assertEquals(oracle.id(text).state, midend.serialise().serialise())
            assertEquals(text, midend.serialise().serialise())
            assertTrue(midend.canUndo)
        }

    @Test
    fun `an empty move is an accepted copy`() = OracleClient().use { oracle ->
        val text = historySave(
            desc = "4,4,m9eed07198e89048f2e4d0",
            privDesc = "m9eed07198e89048f2e4d0",
            history = listOf("MOVE" to ""),
            statePos = 2,
        )
        val midend = Midend.fromSave(Mines, SgtpSave.parse(text))
        assertEquals(2, midend.stateCount)
        assertEquals(oracle.id(text).id, midend.id())
        assertEquals(oracle.id(text).state, midend.serialise().serialise())
        assertEquals(text, midend.serialise().serialise())
    }

    @Test(timeout = 120_000)
    fun `the UI record round-trips in both directions`() = OracleClient().use { oracle ->
        val played = Midend.newGame(Mines, "9x9n10", SEED)
        click(played, 4, 4)
        val template = played.serialise()

        val vectors = listOf(
            "D0", "D0C", "C", "D3x", "D-3", "D3Cjunk", "D+3C", "D 3C",
            " D3", "D3 C", "", "D", "DD3", "D007",
            // glibc reads `%d` through a `long` and keeps the low 32
            // bits; these vectors pin that wrap.
            "D2147483647", "D2147483648", "D4294967296", "D99999999999",
            "D-2147483649", "D-4294967297", "D99999999999999999999999",
            "D-99999999999999999999999",
        )
        for (vector in vectors) {
            val save = SgtpSave.of(
                template.records.map {
                    if (it.key == "UI") SaveRecord("UI", vector) else it
                },
            )
            val text = save.serialise()
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Mines, SgtpSave.parse(text))
            assertEquals("UI '$vector' canonical", expected.state, midend.serialise().serialise())
            assertEquals("UI '$vector' id", expected.id, midend.id())
            assertEquals("UI '$vector' status text", oracleStatus(oracle, text), midend.statusText())
            val encoded = midend.serialise().value("UI")
            assertTrue("UI '$vector' printable", encoded != null && encoded.all { it.code in 33..126 })
        }

        // The win sets the sticky flag, and undo does not clear it.
        val win = Midend.newGame(Mines, "4x4n1", "7")
        click(win, 0, 0)
        click(win, 0, 3)
        assertEquals("D0C", win.serialise().value("UI"))
        val winText = win.serialise().serialise()
        assertEquals(winText, oracle.id(winText).state)
        assertTrue(win.undo())
        assertEquals("D0C", win.serialise().value("UI"))
        assertEquals("D0C", oracle.id(win.serialise().serialise()).state
            .let { SgtpSave.parse(it).value("UI") })

        // A hand-written D2C overlays the fresh UI and stops the clock.
        val handWritten = historySave(
            desc = "0,0,m9690",
            privDesc = "m9690",
            history = emptyList(),
            statePos = 1,
            params = "4x4n1",
            cparams = "4x4n1",
            ui = "D2C",
        )
        val restored = Midend.fromSave(Mines, SgtpSave.parse(handWritten))
        assertEquals("D2C", restored.serialise().value("UI"))
        assertEquals(2, (restored.ui as Mines.Ui).deaths)
        assertTrue((restored.ui as Mines.Ui).completed)
        assertFalse(restored.timing)
        assertEquals(oracleStatus(oracle, handWritten), restored.statusText())
    }

    @Test
    fun `TIME round-trips with the C general format`() = OracleClient().use { oracle ->
        val played = Midend.newGame(Mines, "9x9n10", SEED)
        click(played, 4, 4)
        val template = played.serialise()

        for (time in listOf(
            "0", "3.5", "61.25", "59.999", "1000000", "1e6", "0.0001", "abc",
            // `atof` takes the numeric prefix of a hand-written record.
            "3.5junk", "61.25x", " 3.5", "1e2junk", ".5junk", "-2.5junk",
            "0x10", "0x1.8p1",
        )) {
            val save = SgtpSave.of(
                template.records.map {
                    if (it.key == "TIME") SaveRecord("TIME", time) else it
                },
            )
            val text = save.serialise()
            val expected = oracle.id(text)
            val midend = Midend.fromSave(Mines, SgtpSave.parse(text))
            assertEquals("TIME '$time' canonical", expected.state, midend.serialise().serialise())
            assertEquals("TIME '$time' id", expected.id, midend.id())
            assertEquals("TIME '$time' status text", oracleStatus(oracle, text), midend.statusText())
        }

        // A non-finite clock is a hand-written record: `%g` of a finite
        // elapsed cannot produce one. The save text still round-trips,
        // but the C `(int)me->elapsed` in the status prefix is undefined
        // for it, so the status text is a defined Kotlin result pinned
        // in `MinesTest`, not a C comparison.
        for (time in listOf("inf", "-infinity", "nan", "1e999")) {
            val save = SgtpSave.of(
                template.records.map {
                    if (it.key == "TIME") SaveRecord("TIME", time) else it
                },
            )
            val text = save.serialise()
            val midend = Midend.fromSave(Mines, SgtpSave.parse(text))
            assertEquals(
                "TIME '$time' canonical",
                oracle.id(text).state,
                midend.serialise().serialise(),
            )
        }

        // The absence of TIME loads at zero and the writer restores it.
        val noTime = SgtpSave.of(template.records.filterNot { it.key == "TIME" })
        val zero = Midend.fromSave(Mines, noTime)
        assertEquals(0f, zero.elapsed)
        assertEquals("0", zero.serialise().value("TIME"))
        assertEquals(oracle.id(noTime.serialise()).state, zero.serialise().serialise())
    }

    @Test
    fun `an invalid PRIVDESC and a move after death are rejected`() =
        OracleClient().use { oracle ->
            val badPrivate = SgtpSave.of(
                listOf(
                    SaveRecord("SAVEFILE", SgtpSave.MAGIC),
                    SaveRecord("VERSION", SgtpSave.VERSION),
                    SaveRecord("GAME", Mines.name),
                    SaveRecord("PARAMS", "9x9n8"),
                    SaveRecord("CPARAMS", "9x9n10"),
                    SaveRecord("DESC", "4,4,m9eed07198e89048f2e4d0"),
                    SaveRecord("PRIVDESC", "mzz"),
                    SaveRecord("UI", "D0"),
                    SaveRecord("TIME", "0"),
                    SaveRecord("NSTATES", "1"),
                    SaveRecord("STATEPOS", "1"),
                ),
            ).serialise()
            val error = try {
                oracle.id(badPrivate)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("Game private description in save file is invalid", error)
            try {
                Midend.fromSave(Mines, SgtpSave.parse(badPrivate))
                fail("Kotlin accepted an invalid PRIVDESC")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("game private description"))
            }

            // Death by clicking the mine at (0,0), then an appended
            // move that C rejects.
            val dead = Midend.newGame(Mines, "9x9n10", SEED)
            click(dead, 4, 4)
            click(dead, 0, 0)
            assertTrue((dead.state as Mines.State).dead)
            val deadSave = dead.serialise()
            val historyKeys = setOf("MOVE", "SOLVE", "RESTART")
            val prefix = deadSave.records.takeWhile { it.key !in historyKeys }
                .filterNot { it.key == "NSTATES" || it.key == "STATEPOS" }
            val history = deadSave.records.filter { it.key in historyKeys }
            val withMove = SgtpSave.of(
                prefix + listOf(
                    SaveRecord("NSTATES", "4"),
                    SaveRecord("STATEPOS", "4"),
                ) + history + SaveRecord("MOVE", "F1,2"),
            ).serialise()
            val deadError = try {
                oracle.id(withMove)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals("Save file contained an invalid move", deadError)
            try {
                Midend.fromSave(Mines, SgtpSave.parse(withMove))
                fail("Kotlin accepted a move after death")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("invalid move"))
            }
        }

    @Test
    fun `undo does not revert the superseded id`() {
        val midend = Midend.newGame(Mines, "9x9n10", SEED)
        click(midend, 4, 4)
        val clickedId = midend.id()
        assertTrue(midend.undo())
        assertEquals(clickedId, midend.id())
        assertEquals("4,4,m9eed07198e89048f2e4d0", midend.desc)
        assertEquals("m9eed07198e89048f2e4d0", midend.privDesc)
    }

    private fun click(midend: Midend, x: Int, y: Int) {
        val px = x * tile + 8 + tile / 2
        val py = y * tile + 8 + tile / 2
        midend.processKey(px, py, Buttons.LEFT_BUTTON, tile)
        midend.processKey(px, py, Buttons.LEFT_RELEASE, tile)
    }

    private fun rightClick(midend: Midend, x: Int, y: Int) {
        val px = x * tile + 8 + tile / 2
        val py = y * tile + 8 + tile / 2
        midend.processKey(px, py, Buttons.RIGHT_BUTTON, tile)
        midend.processKey(px, py, Buttons.RIGHT_RELEASE, tile)
    }

    private fun middleClick(midend: Midend, x: Int, y: Int) {
        val px = x * tile + 8 + tile / 2
        val py = y * tile + 8 + tile / 2
        midend.processKey(px, py, Buttons.MIDDLE_BUTTON, tile)
        midend.processKey(px, py, Buttons.MIDDLE_RELEASE, tile)
    }

    private fun oracleStatus(oracle: OracleClient, state: String): String =
        (oracle.trace(state).field("ops") as JsonValue.Arr).items
            .filterIsInstance<JsonValue.Obj>()
            .first { it.string("op") == "status" }
            .string("text")

    private fun historySave(
        desc: String,
        privDesc: String?,
        history: List<Pair<String, String>>,
        statePos: Int,
        params: String = "9x9n8",
        cparams: String = "9x9n10",
        ui: String = "D0",
    ): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Mines.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", cparams),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", desc),
        )
        if (privDesc != null) records += SaveRecord("PRIVDESC", privDesc)
        records += SaveRecord("UI", ui)
        records += SaveRecord("TIME", "0")
        records += SaveRecord("NSTATES", (history.size + 1).toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for ((key, value) in history) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private companion object {
        const val SEED = "12345"
    }
}
