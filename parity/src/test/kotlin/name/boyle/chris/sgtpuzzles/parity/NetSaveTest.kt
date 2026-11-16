package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: net.
 *
 * Every corpus save round-trips byte for byte and reloads in the
 * oracle. Kotlin saves for every preset load in C. The history cases
 * cover a rotate, a lock, a jumble, an undone position with a live redo
 * branch, a `SOLVE`, and a `RESTART`. The `UI` record round-trips in
 * both directions, and a save without it keeps the base UI.
 */
class NetSaveTest {

    private companion object {
        const val SEED = "12345"
        const val TILE = 32
        const val FIVE = "5x5:1863151ea86e7e3c7abc21a32"
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
        moves: List<String>,
        statePos: Int = moves.size + 1,
    ): String {
        val save = SgtpSave.parse(fresh)
        val records = ArrayList(save.records.takeWhile { it.key != "NSTATES" })
        records += SaveRecord("NSTATES", (moves.size + 1).toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    private fun replaceRecord(save: String, key: String, value: String): String {
        val records = SgtpSave.parse(save).records.map {
            if (it.key == key) SaveRecord(key, value) else it
        }
        return SgtpSave.of(records).serialise()
    }

    private fun withoutRecord(save: String, key: String): String {
        val records = SgtpSave.parse(save).records.filter { it.key != key }
        return SgtpSave.of(records).serialise()
    }

    @Test
    fun `every corpus save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves/initial/net")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .sorted()
                .toList()
            assertEquals(9, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Net, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertSame(file.name, expected, midend)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())
                assertEquals(
                    "${file.name} keeps AUXINFO",
                    SgtpSave.parse(text).value("AUXINFO"),
                    midend.serialise().value("AUXINFO"),
                )
                assertEquals(
                    "${file.name} keeps UI",
                    SgtpSave.parse(text).value("UI"),
                    midend.serialise().value("UI"),
                )
                assertFalse(midend.canUndo)
                assertFalse(midend.canRedo)
            }

            assertEquals(
                File(testdata, "saves/initial/net/5x5.sgtp").readText(),
                File(testdata, "saves/initial/net/default.sgtp").readText(),
            )
            assertEquals(
                "O0,0;C2,2",
                SgtpSave.parse(File(testdata, "saves/initial/net/5x5w.sgtp").readText())
                    .value("UI"),
            )
        }

    @Test
    fun `C loads a Kotlin save for every preset`() = OracleClient().use { oracle ->
        for (params in Net.presets().map { it.params }) {
            val kotlin = Midend.newGame(Net, params, SEED)
            val text = kotlin.serialise().serialise()
            val expected = oracle.id(text)
            assertSame("'$params'", expected, kotlin)
        }
    }

    @Test
    fun `the UI record round-trips in both directions`() = OracleClient().use { oracle ->
        val save = Midend.newGame(Net, "5x5w", SEED).serialise().serialise()
        val moved = oracle.keys(
            save,
            listOf(
                Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
                Buttons.CURSOR_DOWN or Buttons.MOD_SHFT,
                Buttons.CURSOR_LEFT or Buttons.MOD_CTRL,
            ),
        )
        assertEquals("O1,1;C1,2", SgtpSave.parse(moved.state).value("UI"))

        val kotlin = Midend.fromSave(Net, SgtpSave.parse(moved.state))
        assertEquals(moved.state, kotlin.serialise().serialise())
        assertEquals("O1,1;C1,2", kotlin.serialise().value("UI"))

        // A hand-written record with spaces and trailing junk follows
        // the sscanf rules.
        val handWritten = replaceRecord(save, "UI", "O 1, 1;C 3, 2junk")
        val expected = oracle.id(handWritten)
        val actual = Midend.fromSave(Net, SgtpSave.parse(handWritten))
        assertEquals(expected.state, actual.serialise().serialise())
        assertEquals("O1,1;C3,2", actual.serialise().value("UI"))

        // An out-of-range pair is ignored and the base UI keeps its
        // centre value.
        val ranged = replaceRecord(save, "UI", "O9,0;C3,9")
        val rangedExpected = oracle.id(ranged)
        val rangedActual = Midend.fromSave(Net, SgtpSave.parse(ranged))
        assertEquals(rangedExpected.state, rangedActual.serialise().serialise())
        assertEquals("O0,0;C2,2", rangedActual.serialise().value("UI"))
    }

    @Test
    fun `the AUXINFO record round-trips`() = OracleClient().use { oracle ->
        val text = Midend.newGame(Net, "5x5", SEED).serialise().serialise()
        val aux = SgtpSave.parse(text).value("AUXINFO")!!
        assertTrue(aux.isNotEmpty())
        val reloaded = Midend.fromSave(Net, SgtpSave.parse(text))
        assertEquals(aux, reloaded.serialise().value("AUXINFO"))
        assertEquals(aux, oracle.id(text).state.let { SgtpSave.parse(it).value("AUXINFO") })

        // A hand-written even-length record survives the decode and
        // encode pair unchanged.
        val handWritten = replaceRecord(text, "AUXINFO", "0011")
        val expected = oracle.id(handWritten)
        val actual = Midend.fromSave(Net, SgtpSave.parse(handWritten))
        assertEquals("0011", actual.serialise().value("AUXINFO"))
        assertEquals(expected.id, actual.id())
        assertEquals(expected.state, actual.serialise().serialise())
    }

    @Test
    fun `the fallback solver matches the oracle when AUXINFO is absent`() =
        OracleClient().use { oracle ->
            for (params in listOf("5x5", "5x5a", "5x5b0.5", "7x7w")) {
                val save = withoutRecord(
                    Midend.newGame(Net, params, SEED).serialise().serialise(),
                    "AUXINFO",
                )
                val expected = oracle.solve(save)
                val midend = Midend.fromSave(Net, SgtpSave.parse(save))
                assertTrue(midend.solve())
                assertEquals("'$params' id", expected.id, midend.id())
                assertEquals("'$params' state", expected.state, midend.serialise().serialise())
                assertTrue(midend.serialise().values("SOLVE").single().startsWith("S"))
            }

            // The same after a real move: C solves the first state and
            // diffs the current one.
            val save = withoutRecord(
                Midend.newGame(Net, "5x5", SEED).serialise().serialise(),
                "AUXINFO",
            )
            val played = oracle.pointsSized(
                save,
                listOf(
                    OracleClient.Point(5 + 16, 5 + 16, Buttons.LEFT_BUTTON),
                    OracleClient.Point(5 + 16, 5 + 16, Buttons.LEFT_RELEASE),
                ),
            ).state
            val expected = oracle.solve(played.state)
            val midend = Midend.fromSave(Net, SgtpSave.parse(played.state))
            assertTrue(midend.solve())
            assertEquals(expected.id, midend.id())
            assertEquals(expected.state, midend.serialise().serialise())
        }

    @Test
    fun `the id short form drops the barrier and the flag`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Net, "5x5b0.5a", SEED)
            val save = midend.serialise()
            assertEquals("5x5", save.value("PARAMS"))
            assertEquals("5x5b0.5a", save.value("CPARAMS"))
            assertTrue(midend.id().startsWith("5x5:"))
            assertEquals("5x5", midend.id().substringBefore(':'))
            assertTrue(save.value("DESC")!!.contains("v") || save.value("DESC")!!.contains("h"))
            assertEquals(midend.id(), oracle.id(save.serialise()).id)

            val reloaded = Midend.fromSave(Net, save)
            assertEquals(save.serialise(), reloaded.serialise().serialise())
        }

    @Test
    fun `a played history round-trips`() = OracleClient().use { oracle ->
        val save = Midend.newGame(Net, "5x5", SEED).serialise().serialise()

        // Rotate, lock, and jumble in one midend. The jumble uses the
        // oracle's NUL platform seed, so the save matches C exactly.
        val expected = oracle.pointsSized(
            replaceRecord(save, "UI", "O0,0;C2,2"),
            listOf(
                OracleClient.Point(5 + 16, 5 + 16, Buttons.LEFT_BUTTON),
                OracleClient.Point(5 + 16, 5 + 16, Buttons.LEFT_RELEASE),
                OracleClient.Point(5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON),
            ),
        ).state
        val jumbled = oracle.key(expected.state, 0, 0, 'J'.code)
        val kotlin = Midend.fromSave(Net, SgtpSave.parse(save))
        kotlin.processKey(5 + 16, 5 + 16, Buttons.LEFT_BUTTON, TILE)
        kotlin.processKey(5 + 16, 5 + 16, Buttons.LEFT_RELEASE, TILE)
        kotlin.processKey(5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON, TILE)
        kotlin.processKey(0, 0, 'J'.code, TILE)
        assertEquals(jumbled.state, kotlin.serialise().serialise())
        assertEquals(jumbled.id, kotlin.id())
        assertEquals(4, kotlin.stateCount)

        // An undone position with a live redo branch.
        assertTrue(kotlin.undo())
        val undone = kotlin.serialise().serialise()
        assertEquals(4, SgtpSave.parse(undone).value("NSTATES")!!.toInt())
        assertEquals(3, SgtpSave.parse(undone).value("STATEPOS")!!.toInt())
        val reloadedUndone = Midend.fromSave(Net, SgtpSave.parse(undone))
        assertEquals(undone, reloadedUndone.serialise().serialise())
        assertTrue(reloadedUndone.canRedo)
        assertTrue(reloadedUndone.redo())
        assertEquals(jumbled.state, reloadedUndone.serialise().serialise())

        // A SOLVE and a RESTART.
        val solved = oracle.solve(jumbled.state).state
        val solvedKotlin = Midend.fromSave(Net, SgtpSave.parse(jumbled.state))
        assertTrue(solvedKotlin.solve())
        assertEquals(solved, solvedKotlin.serialise().serialise())
        assertTrue(solvedKotlin.serialise().values("SOLVE").single().startsWith("S"))
        assertTrue(solvedKotlin.restart())
        assertEquals(
            solvedKotlin.serialise().serialise(),
            oracle.id(solvedKotlin.serialise().serialise()).state,
        )
        assertTrue(solvedKotlin.serialise().records.any { it.key == "RESTART" })
    }

    @Test
    fun `a save without a UI record keeps the base UI`() =
        OracleClient().use { oracle ->
            val withUi = Midend.newGame(Net, "5x5w", SEED).serialise().serialise()
            val noUi = withoutRecord(withUi, "UI")
            assertEquals(null, SgtpSave.parse(noUi).value("UI"))

            val expected = oracle.key(noUi, 0, 0, 'J'.code).state
            val kotlin = Midend.fromSave(Net, SgtpSave.parse(noUi))
            assertEquals("O0,0;C2,2", kotlin.serialise().value("UI"))
            kotlin.processKey(0, 0, 'J'.code, TILE)
            assertEquals(expected, kotlin.serialise().serialise())

            // An explicit platform seed still does not enter the save.
            val seed = ByteArray(16) { (it + 5).toByte() }
            val other = Midend.fromSave(Net, SgtpSave.parse(noUi), seed)
            other.processKey(0, 0, 'J'.code, TILE)
            val otherMove = other.serialise().values("MOVE").single()
            val nulMove = kotlin.serialise().values("MOVE").single()
            assertTrue(otherMove.startsWith("J"))
            org.junit.Assert.assertNotEquals(nulMove, otherMove)
            assertEquals("O0,0;C2,2", other.serialise().value("UI"))
            assertEquals(2, other.stateCount)
            assertEquals("2", other.serialise().value("NSTATES"))
        }

    @Test
    fun `the frozen game id is the short form`() = OracleClient().use { oracle ->
        val text = File(testdata, "saves/initial/net/5x5w.sgtp").readText()
        val midend = Midend.fromSave(Net, SgtpSave.parse(text))
        assertEquals(FIVE, Midend.fromSave(
            Net,
            SgtpSave.parse(File(testdata, "saves/initial/net/5x5.sgtp").readText()),
        ).id())
        assertEquals("5x5w:8642ec1e6b11ae6cac23c395b", midend.id())
        assertEquals(midend.id(), oracle.id(text).id)
        assertEquals("5x5w", midend.serialise().value("PARAMS"))
        assertEquals("5x5w", midend.serialise().value("CPARAMS"))
    }
}
