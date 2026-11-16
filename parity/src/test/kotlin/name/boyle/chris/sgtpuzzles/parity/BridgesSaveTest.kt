package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * bridges.
 *
 * Every corpus save round-trips byte for byte and reloads in the oracle.
 * Kotlin saves for every preset load in C. The history cases cover a
 * played `L`, `N`, and `M`, an undone position with a live redo branch,
 * a `SOLVE`, a `RESTART`, and a hint move that starts with `S`. The
 * `PARAMS`/`CPARAMS` split and the absent `UI` record are pinned.
 */
class BridgesSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 24
        const val PARAMS = "7x7i30e10m2d0"
    }

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    private fun assertSame(what: String, expected: GameState, actual: Midend) {
        assertEquals("$what id", expected.id, actual.id())
        assertEquals("$what state", expected.state, actual.serialise().serialise())
        assertEquals("$what status", expected.status, actual.status())
        assertEquals("$what canUndo", expected.canUndo, actual.canUndo)
        assertEquals("$what canRedo", expected.canRedo, actual.canRedo)
    }

    private fun withHistory(
        fresh: String,
        moves: List<Pair<String, String>>,
        statePos: Int = moves.size + 1,
    ): String {
        val save = SgtpSave.parse(fresh)
        val records = ArrayList(save.records.takeWhile { it.key != "NSTATES" })
        records += SaveRecord("NSTATES", (moves.size + 1).toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for ((key, value) in moves) records += SaveRecord(key, value)
        return SgtpSave.of(records).serialise()
    }

    private fun withUi(fresh: String): String {
        val save = SgtpSave.parse(fresh)
        val records = ArrayList<SaveRecord>()
        for (record in save.records) {
            if (record.key == "NSTATES") records += SaveRecord("UI", "x")
            records += record
        }
        return SgtpSave.of(records).serialise()
    }

    @Test
    fun `every corpus save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/bridges")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(10, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Bridges, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} keeps AUXINFO",
                    SgtpSave.parse(text).value("AUXINFO"),
                    midend.serialise().value("AUXINFO"),
                )
                assertFalse(
                    "${file.name} has no UI record",
                    midend.serialise().records.any { it.key == "UI" },
                )
            }

            assertEquals(
                File(testdata, "saves/initial/bridges/7x7i30e10m2d0.sgtp").readText(),
                File(testdata, "saves/initial/bridges/default.sgtp").readText(),
            )
        }

    @Test
    fun `the PARAMS and CPARAMS split is pinned`() = OracleClient().use { oracle ->
        for ((params, longTerm) in listOf(
            "7x7i30e10m2d1" to "7x7i30e10m2d0",
            "10x10i30e10m2d1" to "10x10i30e10m2d0",
            "15x15i30e10m2d2" to "15x15i30e10m2d0",
        )) {
            val text = File(testdata, "saves/initial/bridges/$params.sgtp").readText()
            val save = SgtpSave.parse(text)
            assertEquals("$params PARAMS", longTerm, save.value("PARAMS"))
            assertEquals("$params CPARAMS", params, save.value("CPARAMS"))
            assertNull("$params no UI", save.value("UI"))
            assertEquals(
                "$params short id",
                Bridges.encodeParams(Bridges.decodeParams(longTerm), false),
                Midend.fromSave(Bridges, save).id().substringBefore(':'),
            )

            val midend = Midend.fromSave(Bridges, save)
            assertEquals(text, midend.serialise().serialise())
            assertSame("$params", oracle.id(text), midend)
        }

        // The literal records of the pinned non-Easy preset.
        val text = File(testdata, "saves/initial/bridges/7x7i30e10m2d1.sgtp").readText()
        assertTrue(text.contains("PARAMS  :13:7x7i30e10m2d0\n"))
        assertTrue(text.contains("CPARAMS :13:7x7i30e10m2d1\n"))
    }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (params in Bridges.presets().map { it.params }) {
                val midend = Midend.newGame(Bridges, params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)
                assertEquals("$params id", midend.id(), loaded.id)
                assertEquals("$params canonical text", text, loaded.state)
                assertEquals("$params text", midend.textFormat(), oracle.text(text))
                assertEquals("$params status", midend.status(), loaded.status)
                assertFalse("$params starts with no undo", loaded.canUndo)
                assertFalse("$params starts with no redo", loaded.canRedo)
                assertTrue("$params carries AUXINFO", SgtpSave.parse(text).value("AUXINFO") != null)
            }
        }

    @Test
    fun `an oracle save loads in Kotlin and round-trips`() =
        OracleClient().use { oracle ->
            for (params in Bridges.presets().map { it.params }) {
                val text = oracle.newGame("bridges", params, SEED).state
                val midend = Midend.fromSave(Bridges, SgtpSave.parse(text))
                assertEquals("$params", text, midend.serialise().serialise())
                assertEquals("$params id", oracle.id(text).id, midend.id())
                assertEquals("$params text", oracle.text(text), midend.textFormat())
            }
        }

    @Test
    fun `a played L N and M and an undone redo branch round-trip`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Bridges, PARAMS, SEED)
            // A bridge from (0,0) to (6,0).
            for (point in listOf(
                OracleClient.Point(TILE, TILE, Buttons.LEFT_BUTTON),
                OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_DRAG),
                OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_RELEASE),
            )) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            // A no-line on the clear edge from (0,0) down to (0,2).
            for (point in listOf(
                OracleClient.Point(TILE, TILE, Buttons.RIGHT_BUTTON),
                OracleClient.Point(TILE, 3 * TILE, Buttons.RIGHT_DRAG),
                OracleClient.Point(TILE, 3 * TILE, Buttons.RIGHT_RELEASE),
            )) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            // A lock on (6,0).
            for (point in listOf(
                OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_BUTTON),
                OracleClient.Point(7 * TILE, TILE, Buttons.LEFT_RELEASE),
            )) {
                midend.processKey(point.x, point.y, point.button, TILE)
            }
            assertEquals(
                listOf("L0,0,6,0,1", "N0,0,0,2", "M6,0"),
                midend.serialise().values("MOVE"),
            )
            val played = midend.serialise().serialise()
            assertSame("played", oracle.id(played), midend)

            val loaded = Midend.fromSave(Bridges, SgtpSave.parse(played))
            assertEquals(played, loaded.serialise().serialise())
            assertEquals(4, loaded.stateCount)

            val undoneExpected = oracle.undo(played)
            assertTrue(midend.undo())
            val undone = midend.serialise().serialise()
            assertSame("undone", undoneExpected, midend)
            assertEquals("4", SgtpSave.parse(undone).value("NSTATES"))
            assertEquals("3", SgtpSave.parse(undone).value("STATEPOS"))
            assertTrue(midend.canRedo)

            assertTrue(midend.redo())
            assertSame("redone", oracle.redo(undone), midend)
        }

    @Test
    fun `a SOLVE and a RESTART round-trip in both directions`() =
        OracleClient().use { oracle ->
            val solved = Midend.newGame(Bridges, PARAMS, SEED)
            assertTrue(solved.solve())
            val solveText = solved.serialise().serialise()
            assertEquals(1, SgtpSave.parse(solveText).values("SOLVE").size)
            assertSame("solve save", oracle.id(solveText), solved)

            val loaded = Midend.fromSave(Bridges, SgtpSave.parse(solveText))
            assertEquals(solveText, loaded.serialise().serialise())
            assertEquals(1, loaded.status())
            assertFalse(loaded.takeCompletedEvent())

            val restarted = Midend.newGame(Bridges, PARAMS, SEED)
            for (point in listOf(
                OracleClient.Point(TILE, TILE, Buttons.LEFT_BUTTON),
                OracleClient.Point(TILE, TILE, Buttons.LEFT_RELEASE),
            )) {
                restarted.processKey(point.x, point.y, point.button, TILE)
            }
            assertTrue(restarted.restart())
            val restartText = restarted.serialise().serialise()
            assertEquals(1, SgtpSave.parse(restartText).values("RESTART").size)
            assertSame("restart save", oracle.id(restartText), restarted)

            val restartLoaded = Midend.fromSave(Bridges, SgtpSave.parse(restartText))
            assertEquals(restartText, restartLoaded.serialise().serialise())
            assertTrue(restartLoaded.canUndo)
            assertFalse(restartLoaded.canRedo)
        }

    @Test
    fun `a hint move that starts with S round-trips`() = OracleClient().use { oracle ->
        val midend = Midend.newGame(Bridges, PARAMS, SEED)
        midend.processKey(0, 0, 'h'.code, TILE)
        val text = midend.serialise().serialise()
        val move = SgtpSave.parse(text).values("MOVE").single()
        assertTrue(move.startsWith("S"))
        assertSame("hint save", oracle.id(text), midend)

        val loaded = Midend.fromSave(Bridges, SgtpSave.parse(text))
        assertEquals(text, loaded.serialise().serialise())
        assertTrue((loaded.state as Bridges.State).solved)
    }

    @Test
    fun `a hand-written UI record is ignored`() = OracleClient().use { oracle ->
        val save = oracle.newGame("bridges", PARAMS, SEED).state
        val withUi = withUi(save)
        val expected = oracle.id(withUi)
        val midend = Midend.fromSave(Bridges, SgtpSave.parse(withUi))
        assertSame("UI record", expected, midend)
        assertFalse(midend.serialise().records.any { it.key == "UI" })
        assertEquals(
            SgtpSave.parse(save).value("AUXINFO"),
            midend.serialise().value("AUXINFO"),
        )
    }

    @Test
    fun `a corrupt description fails in both engines`() = OracleClient().use { oracle ->
        val base = oracle.newGame("bridges", PARAMS, SEED).state
        for (desc in listOf(
            "11" + "g",          // joined islands
            "a1",               // too few islands
            "3e2g6a3c2i1b3h4d4b", // too long
            "3e2g6a3c2i1b3h4d4",  // too short
            "3e2g6a3c2i1b3h4d4z", // longer than expected: z is a 26-cell run
        )) {
            val save = SgtpSave.parse(base)
            val records = save.records.map {
                when (it.key) {
                    "DESC" -> SaveRecord("DESC", desc)
                    "AUXINFO" -> SaveRecord("AUXINFO", "")
                    else -> it
                }
            }
            val text = SgtpSave.of(records).serialise()
            val oracleFailed = try {
                oracle.id(text)
                false
            } catch (e: OracleException) {
                true
            }
            val kotlinFailed = try {
                Midend.fromSave(Bridges, SgtpSave.parse(text))
                false
            } catch (e: RuntimeException) {
                true
            }
            assertEquals("'$desc' rejected", oracleFailed, kotlinFailed)
            assertTrue("'$desc' must be rejected", oracleFailed)
        }
    }
}
